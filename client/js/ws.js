import { encode, decode } from '@msgpack/msgpack';
import { getToken } from './api.js';

/* ── Opcodes ── */
export const OP = {
  MSG_SEND:      0x01,
  MSG_RECV:      0x02,
  MSG_ACK:       0x03,
  MSG_DELIVERED: 0x04,
  OFFLINE_BATCH: 0x05,
  PING:          0x06,
  PONG:          0x07,
  CHAT_PURGE:     0x08,
  CHAT_PURGE_ACK: 0x09,
  KEY_FETCH_REQ: 0x10,
  KEY_FETCH_RESP: 0x11,
  KEY_PUBLISH:    0x12,
  KEY_BUNDLE_RESP: 0x13,
  KEY_BUNDLE_REQ:  0x14,
  MSG_RETRY_REQ:   0x16,
  MSG_RETRY_RESP:  0x17,
  MSG_READ:        0x18,
  PAIRING_HISTORY_READY: 0x19,
  PAIRING_CLAIMED: 0x1a,
  MSG_STATUS_BATCH: 0x1b,
  USER_AVATAR_UPDATE: 0x1c,
  PRESENCE_UPDATE: 0x1d,
  SERVER_SHUTDOWN: 0x1e,
  GROUP_MESSAGE_SEND:      0x20,
  GROUP_MESSAGE_RECV:      0x21,
  GROUP_MESSAGE_ACK:       0x22,
  GROUP_KEY_AVAILABLE:     0x23,
  GROUP_MEMBER_CHANGED:    0x24,
  GROUP_MESSAGE_DELIVERED: 0x25,
  GROUP_MESSAGE_READ:      0x26,
  GROUP_HISTORY_READY:     0x27,
  GROUP_AVATAR_UPDATE:     0x28,
};

const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
const WS_URL = `${wsProtocol}//${window.location.host}/api/v1/ws`;
const PING_INTERVAL = 25_000;
const RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 15000, 30000];
const MAX_FRAME_SIZE = 10 * 1024 * 1024; // 10MB limit
const PONG_TIMEOUT = 10_000;

class WSManager {
  constructor() {
    this._ws = null;
    this._handlers = new Map();
    this._pendingReplies = new Map();
    this._reconnectAttempt = 0;
    this._pingTimer = null;
    this._pongTimer = null;
    this._reconnectTimer = null;
    this._manualClose = false;
    this._connected = false;
    this._connectListeners = [];
    this._disconnectListeners = [];
    this._queue = [];
    this._requestQueue = Promise.resolve();
    this._lastConnectTime = 0;
  }

  connect() {
    const token = getToken();
    if (!token) return;

    this._manualClose = false;
    this._doConnect(token);
  }

  /**
   * Called whenever any REST request succeeds. If we're currently disconnected
   * and waiting on a reconnect timer, treat that as a sign the server is
   * reachable and reconnect immediately instead of waiting out the backoff.
   */
  notifyRestSuccess() {
    if (this._manualClose || this._connected) return;
    if (this._ws && this._ws.readyState === WebSocket.CONNECTING) return;
    // Throttle reconnects triggered by REST if less than 5 seconds have passed since last connect attempt
    if (Date.now() - this._lastConnectTime < 5000) return;
    const token = getToken();
    if (!token) return;
    this._clearTimers();
    this._reconnectAttempt = 0;
    this._doConnect(token);
  }

  disconnect() {
    this._manualClose = true;
    this._clearTimers();
    if (this._ws) {
      this._ws.close();
      this._ws = null;
    }
    this._connected = false;
    this._pendingReplies.clear();
  }

  /**
   * Closes the socket after a server-initiated shutdown notice, without
   * setting _manualClose - the server closes the connection on its own right
   * after this anyway, so this just avoids waiting for that round trip. The
   * normal onclose -> _scheduleReconnect path still runs, so the client keeps
   * retrying (and notifyRestSuccess still reconnects immediately once REST
   * calls start succeeding again).
   */
  closeForServerShutdown() {
    if (this._ws) {
      this._ws.close();
    }
  }

  get connected() { return this._connected; }

  isConnected() { return this._connected; }

  off(opcode, handler) {
    const list = this._handlers.get(opcode) || [];
    const idx = list.indexOf(handler);
    if (idx !== -1) list.splice(idx, 1);
  }

  on(opcode, handler) {
    if (!this._handlers.has(opcode)) this._handlers.set(opcode, []);
    this._handlers.get(opcode).push(handler);
    return () => {
      const list = this._handlers.get(opcode) || [];
      const idx = list.indexOf(handler);
      if (idx !== -1) list.splice(idx, 1);
    };
  }

  onConnect(fn) {
    this._connectListeners.push(fn);
    return () => {
      const i = this._connectListeners.indexOf(fn);
      if (i !== -1) this._connectListeners.splice(i, 1);
    };
  }

  onDisconnect(fn) {
    this._disconnectListeners.push(fn);
    return () => {
      const i = this._disconnectListeners.indexOf(fn);
      if (i !== -1) this._disconnectListeners.splice(i, 1);
    };
  }

  send(opcode, payload) {
    if (!this._ws) {
      console.warn('[ws] No WebSocket instance, dropping send', opcode);
      return false;
    }
    if (this._ws.readyState === WebSocket.CONNECTING) {
      this._queue.push(() => this.send(opcode, payload));
      return true;
    }
    if (this._ws.readyState !== WebSocket.OPEN) {
      console.warn('[ws] Not connected (state=' + this._ws.readyState + '), dropping send', opcode);
      return false;
    }
    const payloadBytes = encode(payload);
    const frame = new Uint8Array(1 + payloadBytes.length);
    frame[0] = opcode;
    frame.set(payloadBytes, 1);
    this._ws.send(frame.buffer);
    return true;
  }

  /* Send and wait for a specific reply opcode or matching req_id */
  request(sendOp, sendPayload, replyOp, timeoutMs = 10_000) {
    const reqId = window.crypto && window.crypto.randomUUID ? window.crypto.randomUUID() : String(Date.now() + Math.random());
    const payloadWithReq = { ...(sendPayload || {}), req_id: reqId };

    const next = () => new Promise((resolve, reject) => {
      const replyKey = `${replyOp}:${reqId}`;

      const timer = setTimeout(() => {
        this._pendingReplies.delete(replyKey);
        this._pendingReplies.delete(replyOp);
        reject(new Error(`WS request timeout op=${sendOp}`));
      }, timeoutMs);

      const callback = (data) => {
        clearTimeout(timer);
        this._pendingReplies.delete(replyKey);
        this._pendingReplies.delete(replyOp);
        resolve(data);
      };

      // Store by key `replyOp:reqId` first, fallback to opcode
      this._pendingReplies.set(replyKey, callback);
      this._pendingReplies.set(replyOp, callback);

      if (!this.send(sendOp, payloadWithReq)) {
        clearTimeout(timer);
        this._pendingReplies.delete(replyKey);
        this._pendingReplies.delete(replyOp);
        reject(new Error('WebSocket not connected'));
      }
    });

    const result = this._requestQueue.then(next, next);
    this._requestQueue = result.catch(() => {});
    return result;
  }

  /* ── Private ── */

  _doConnect(token) {
    const url = WS_URL;
    this._lastConnectTime = Date.now();

    try {
      this._ws = new WebSocket(url, ["access_token", token]);
      this._ws.binaryType = 'arraybuffer';
    } catch (e) {
      console.error('[ws] Failed to create WebSocket', e);
      this._scheduleReconnect();
      return;
    }

    this._ws.onopen = () => {
      console.log('[ws] Connected');
      this._connected = true;
      this._reconnectAttempt = 0;
      this._startPing();
      this._connectListeners.forEach(fn => fn());

      const q = this._queue;
      this._queue = [];
      q.forEach(fn => fn());
    };

    this._ws.onmessage = (ev) => {
      this._handleFrame(ev.data);
    };

    this._ws.onerror = (e) => {
      console.warn('[ws] Error', e);
    };

    this._ws.onclose = (ev) => {
      console.log('[ws] Closed', ev.code, ev.reason);
      this._connected = false;
      this._clearTimers();
      this._queue = [];
      this._disconnectListeners.forEach(fn => fn());
      if (!this._manualClose) this._scheduleReconnect();
    };
  }

  _handleFrame(buffer) {
    if (!buffer || buffer.byteLength > MAX_FRAME_SIZE) {
      console.warn('[ws] Dropping frame exceeding max size or empty', buffer?.byteLength);
      return;
    }

    try {
      const bytes = new Uint8Array(buffer);
      if (bytes.length < 1) return;

      const opcode = bytes[0];
      const payload = bytes.length > 1 ? decode(bytes.slice(1)) : {};

      /* Handle pong internally */
      if (opcode === OP.PONG) {
        if (this._pongTimer) {
          clearTimeout(this._pongTimer);
          this._pongTimer = null;
        }
        return;
      }

      /* Handle pending replies by req_id or opcode */
      if (payload && payload.req_id) {
        const replyKey = `${opcode}:${payload.req_id}`;
        if (this._pendingReplies.has(replyKey)) {
          const cb = this._pendingReplies.get(replyKey);
          cb(payload);
          return;
        }
      }

      if (this._pendingReplies.has(opcode)) {
        const cb = this._pendingReplies.get(opcode);
        cb(payload);
        return;
      }

      /* Dispatch to registered handlers */
      const handlers = this._handlers.get(opcode) || [];
      handlers.forEach(fn => {
        try { fn(payload); } catch (e) { console.error('[ws] Handler error', e); }
      });
    } catch (err) {
      console.error('[ws] Failed to decode/process frame', err);
    }
  }

  _startPing() {
    this._clearTimers();
    this._pingTimer = setInterval(() => {
      if (this._ws?.readyState === WebSocket.OPEN) {
        this.send(OP.PING, {});
        // Expect PONG response within PONG_TIMEOUT ms
        if (this._pongTimer) clearTimeout(this._pongTimer);
        this._pongTimer = setTimeout(() => {
          console.warn('[ws] PONG timeout - closing stuck connection');
          if (this._ws) this._ws.close();
        }, PONG_TIMEOUT);
      }
    }, PING_INTERVAL);
  }

  _clearTimers() {
    if (this._pingTimer) { clearInterval(this._pingTimer); this._pingTimer = null; }
    if (this._pongTimer) { clearTimeout(this._pongTimer); this._pongTimer = null; }
    if (this._reconnectTimer) { clearTimeout(this._reconnectTimer); this._reconnectTimer = null; }
  }

  _scheduleReconnect() {
    const delay = RECONNECT_DELAYS[Math.min(this._reconnectAttempt, RECONNECT_DELAYS.length - 1)];
    this._reconnectAttempt++;
    console.log(`[ws] Reconnecting in ${delay}ms (attempt ${this._reconnectAttempt})`);
    this._reconnectTimer = setTimeout(() => {
      const token = getToken();
      if (token && !this._manualClose) this._doConnect(token);
    }, delay);
  }
}

export const ws = new WSManager();

window.addEventListener('penik:rest-success', () => ws.notifyRestSuccess());
