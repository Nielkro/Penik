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
  KEY_FETCH_REQ: 0x10,
  KEY_FETCH_RESP: 0x11,
};

const WS_URL = 'ws://localhost:8143/api/v1/ws';
const PING_INTERVAL = 25_000;
const RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 15000, 30000];

class WSManager {
  constructor() {
    this._ws = null;
    this._handlers = new Map();
    this._pendingReplies = new Map();
    this._reconnectAttempt = 0;
    this._pingTimer = null;
    this._reconnectTimer = null;
    this._manualClose = false;
    this._connected = false;
    this._connectListeners = [];
    this._disconnectListeners = [];
    this._queue = [];
  }

  connect() {
    const token = getToken();
    if (!token) return;

    this._manualClose = false;
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

  /* Send and wait for a specific reply opcode */
  request(sendOp, sendPayload, replyOp, timeoutMs = 10_000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this._pendingReplies.delete(replyOp);
        reject(new Error(`WS request timeout op=${sendOp}`));
      }, timeoutMs);

      this._pendingReplies.set(replyOp, (data) => {
        clearTimeout(timer);
        resolve(data);
      });

      if (!this.send(sendOp, sendPayload)) {
        clearTimeout(timer);
        this._pendingReplies.delete(replyOp);
        reject(new Error('WebSocket not connected'));
      }
    });
  }

  /* ── Private ── */

  _doConnect(token) {
    const url = WS_URL;

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
    const bytes = new Uint8Array(buffer);
    if (bytes.length < 1) return;

    const opcode = bytes[0];
    const payload = bytes.length > 1 ? decode(bytes.slice(1)) : {};

    /* Handle pong internally */
    if (opcode === OP.PONG) return;

    /* Handle pending replies first */
    if (this._pendingReplies.has(opcode)) {
      const cb = this._pendingReplies.get(opcode);
      this._pendingReplies.delete(opcode);
      cb(payload);
      return;
    }

    /* Dispatch to registered handlers */
    const handlers = this._handlers.get(opcode) || [];
    handlers.forEach(fn => {
      try { fn(payload); } catch (e) { console.error('[ws] Handler error', e); }
    });
  }

  _startPing() {
    this._clearTimers();
    this._pingTimer = setInterval(() => {
      if (this._ws?.readyState === WebSocket.OPEN) {
        this.send(OP.PING, {});
      }
    }, PING_INTERVAL);
  }

  _clearTimers() {
    if (this._pingTimer) { clearInterval(this._pingTimer); this._pingTimer = null; }
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
