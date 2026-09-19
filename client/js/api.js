export function getApiOrigin() {
  if (typeof window !== 'undefined' && window.__PENIK_API_ORIGIN__) {
    return window.__PENIK_API_ORIGIN__;
  }
  if (typeof window !== 'undefined' && window.localStorage) {
    const saved = window.localStorage.getItem('penik_api_origin');
    if (saved) return saved;
  }
  if (typeof window !== 'undefined') {
    if (window.location.protocol === 'wails:' || window.location.hostname === 'wails.localhost' || window.location.protocol === 'file:') {
      return 'https://api.penik.ru';
    }
    return `${window.location.protocol}//${window.location.host}`;
  }
  return '';
}

export function setApiOrigin(origin) {
  if (typeof window !== 'undefined') {
    window.__PENIK_API_ORIGIN__ = origin;
    if (window.localStorage) {
      window.localStorage.setItem('penik_api_origin', origin);
    }
  }
}

export function getBaseUrl() {
  return `${getApiOrigin()}/api/v1`;
}

export const BASE = `${getApiOrigin()}/api/v1`;

export function getFullApiUrl(urlOrPath) {
  if (!urlOrPath) return '';
  if (urlOrPath.startsWith('http://') || urlOrPath.startsWith('https://') || urlOrPath.startsWith('blob:') || urlOrPath.startsWith('data:')) {
    return urlOrPath;
  }
  const origin = getApiOrigin();
  if (urlOrPath.startsWith('/')) {
    return `${origin}${urlOrPath}`;
  }
  return `${origin}/${urlOrPath}`;
}

// ApiError carries the HTTP status next to the message so callers can branch on
// it — 404 for a missing resource, 410 for an expired CDN link, 0 when the
// request never reached the server.
export class ApiError extends Error {
  /**
   * @param {string} message
   * @param {number} status
   */
  constructor(message, status) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

import { saveSessionToken, getSessionToken, deleteSessionToken } from './storage.js';
import { isDesktop, desktopFetch } from './desktop.js';

let _token = null;

// primeToken loads the persisted session token into memory once at startup and
// migrates any token left in localStorage by an older build. Must be awaited
// before the first synchronous getToken() call on boot.
export async function primeToken() {
  if (_token) return _token;
  const stored = await getSessionToken();
  if (stored) {
    _token = stored;
    return _token;
  }
  const legacy = localStorage.getItem('penik_token');
  if (legacy) {
    _token = legacy;
    await saveSessionToken(legacy);
    localStorage.removeItem('penik_token');
  }
  return _token;
}

export function setToken(t) {
  _token = t;
  // Persist to IndexedDB (not localStorage) so an XSS cannot read it via a
  // synchronous localStorage dump.
  if (t) {
    void saveSessionToken(t);
  } else {
    void deleteSessionToken();
  }
  // Clean up any legacy plaintext copy.
  localStorage.removeItem('penik_token');
}

export function getToken() {
  return _token;
}

function formatHttpError(status, dataOrBody) {
  let serverMsg = '';
  if (typeof dataOrBody === 'object' && dataOrBody?.error) {
    serverMsg = dataOrBody.error;
  } else if (typeof dataOrBody === 'string') {
    try {
      const parsed = JSON.parse(dataOrBody);
      if (parsed && parsed.error) serverMsg = parsed.error;
    } catch {}
    if (!serverMsg && dataOrBody && !dataOrBody.startsWith('<')) {
      serverMsg = dataOrBody.trim();
    }
  }

  if (serverMsg) {
    if (serverMsg === 'unauthorized' || serverMsg === 'invalid username or password') {
      return 'Неверный логин, пароль или сессия истекла';
    }
    if (serverMsg === 'bad request') {
      return 'Некорректные данные запроса';
    }
    if (serverMsg === 'user not found') {
      return 'Пользователь не найден';
    }
    if (serverMsg === 'backup_not_found' || serverMsg === 'backup not found') {
      return 'Резервная копия не найдена';
    }
    return serverMsg;
  }

  switch (status) {
    case 400: return 'Некорректный запрос';
    case 401: return 'Неверный пароль или сессия завершена';
    case 403: return 'Доступ запрещен (CORS или недостаточно прав)';
    case 404: return 'Запрашиваемый ресурс не найден';
    case 409: return 'Запись уже существует';
    case 413: return 'Файл превышает допустимый размер';
    case 429: return 'Слишком много запросов. Пожалуйста, подождите';
    case 500: return 'Внутренняя ошибка сервера';
    case 502:
    case 503:
    case 504: return 'Сервер временно недоступен';
    default: return `Ошибка запроса (${status})`;
  }
}

async function request(method, path, body, opts = {}) {
  const token = opts.token ?? getToken();
  /** @type {Record<string, string>} */
  const headers = {};

  if (token) headers['Authorization'] = `Bearer ${token}`;

  /** @type {RequestInit} */
  const init = { method, headers };

  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }

  if (isDesktop() && window.go?.main?.App?.HttpRequest) {
    const fullUrl = getBaseUrl() + path;
    const bodyStr = body !== undefined ? JSON.stringify(body) : '';
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const resp = await desktopFetch(method, fullUrl, headers, bodyStr);
    if (resp) {
      if (resp.status >= 200 && resp.status < 300) {
        window.dispatchEvent(new Event('penik:rest-success'));
        if (resp.status === 204 || !resp.body) return null;
        try {
          return JSON.parse(resp.body);
        } catch {
          return resp.body;
        }
      }
      const msg = formatHttpError(resp.status, resp.body);
      throw new ApiError(msg, resp.status);
    }
  }

  let res;
  try {
    res = await fetch(getBaseUrl() + path, init);
  } catch (e) {
    throw new ApiError('Нет соединения с сервером (CORS или сервер недоступен)', 0);
  }

  if (res.ok) window.dispatchEvent(new Event('penik:rest-success'));

  if (res.status === 204) return null;

  let data;
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) {
    data = await res.json();
  } else {
    const text = await res.text();
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!res.ok) {
    const msg = formatHttpError(res.status, data);
    throw new ApiError(msg, res.status);
  }

  return data;
}

const get = (path, opts) => request('GET', path, undefined, opts);
const post = (path, body, opts) => request('POST', path, body, opts);
const patch = (path, body, opts) => request('PATCH', path, body, opts);
const put = (path, body, opts) => request('PUT', path, body, opts);
const del = (path, opts) => request('DELETE', path, undefined, opts);

export const apiPost = post;
export const apiGet  = get;
export const apiPatch = patch;
export const apiPut = put;
export const apiDelete = del;

/* ── Auth ── */

export async function register({ username, password, name, ik_pub, spk_pub, spk_sig, opk_pubs, crypto_version = 2 }) {
  return post('/register', { username, password, name, ik_pub, spk_pub, spk_sig, opk_pubs, crypto_version });
}

export async function login({ username, password, crypto_version = 2 }) {
  return post('/login', { username, password, crypto_version });
}

/* ── Keys ── */

export async function uploadOTKs(opk_pubs) {
  return post('/keys/otk', { opk_pubs });
}

/* ── Users ── */

export async function getMe() {
  return get('/users/me');
}

export async function updateMe({ name }) {
  return put('/users/me/name', { name });
}

export async function uploadAvatar(file) {
  const formData = new FormData();
  formData.append('avatar', file);
  const token = getToken();
  /** @type {Record<string, string>} */
  const headers = {};
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(`${getBaseUrl()}/avatar`, {
    method: 'PUT',
    headers,
    body: formData
  });

  if (!res.ok) {
    const text = await res.text();
    let msg = text;
    try {
      const data = JSON.parse(text);
      if (data && data.error) msg = data.error;
    } catch {}
    throw new ApiError(msg || 'Не удалось загрузить аватар', res.status);
  }
  window.dispatchEvent(new Event('penik:rest-success'));
  return true;
}

/**
 * @param {Blob} encryptedFileBlob
 * @param {string} [filename]
 * @param {((loaded: number, total: number) => void)|null} [onProgress]
 * @returns {Promise<string>}
 */
export async function uploadAttachment(encryptedFileBlob, filename = 'encrypted.bin', onProgress = null) {
  const formData = new FormData();
  formData.append('file', encryptedFileBlob, filename);
  const token = getToken();

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${getBaseUrl()}/attachments/upload`, true);
    if (token) {
      xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    }

    if (xhr.upload && onProgress) {
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
          onProgress(e.loaded, e.total);
        }
      };
    }

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        window.dispatchEvent(new Event('penik:rest-success'));
        try {
          const json = JSON.parse(xhr.responseText);
          resolve(json.url);
        } catch (err) {
          reject(new ApiError('Некорректный ответ сервера', xhr.status));
        }
      } else {
        let msg = xhr.responseText;
        try {
          const data = JSON.parse(xhr.responseText);
          if (data && data.error) msg = data.error;
        } catch {}
        reject(new ApiError(msg || 'Не удалось загрузить файл на сервер', xhr.status));
      }
    };

    xhr.onerror = () => {
      reject(new ApiError('Ошибка сети при загрузке файла', 0));
    };

    xhr.onabort = () => {
      reject(new ApiError('Загрузка файла отменена', 0));
    };

    xhr.send(formData);
  });
}

export async function searchUsers(query) {
  const encoded = encodeURIComponent(query);
  return get(`/users/search?q=${encoded}`);
}

export async function getUserById(userId) {
  return get(`/users/${userId}`);
}

// listDevices returns the authenticated user's devices, with is_current flagging
// the device that issued this request.
export async function listDevices() {
  return get('/devices');
}

/* ── Messages (REST fallback) ── */

export async function getMessageHistory(userId, before, limit = 40) {
  let path = `/messages/${userId}?limit=${limit}`;
  if (before) path += `&before=${before}`;
  return get(path);
}

/* ── Call History (with memory caching) ── */

const peerCallsCache = new Map(); // userId -> { ts: number, data: any[] }
let listCallsCache = null; // { ts: number, key: string, data: any[] }
const CALLS_CACHE_TTL = 30 * 1000; // 30 seconds

export function invalidateCallsCache(userId = null) {
  if (userId != null) {
    peerCallsCache.delete(String(userId));
  } else {
    peerCallsCache.clear();
  }
  listCallsCache = null;
}

export async function listCalls(limit = 50, offset = 0, forceRefresh = false) {
  const cacheKey = `${limit}_${offset}`;
  const now = Date.now();
  if (!forceRefresh && listCallsCache && listCallsCache.key === cacheKey && (now - listCallsCache.ts < CALLS_CACHE_TTL)) {
    return listCallsCache.data;
  }
  try {
    const res = await get(`/calls?limit=${limit}&offset=${offset}`);
    const data = Array.isArray(res) ? res : [];
    listCallsCache = { ts: Date.now(), key: cacheKey, data };
    return data;
  } catch (err) {
    if (listCallsCache && listCallsCache.key === cacheKey) return listCallsCache.data;
    throw err;
  }
}

export async function listPeerCalls(userId, limit = 50, forceRefresh = false) {
  const key = String(userId);
  const cached = peerCallsCache.get(key);
  const now = Date.now();
  if (!forceRefresh && cached && (now - cached.ts < CALLS_CACHE_TTL)) {
    return cached.data;
  }
  try {
    const res = await get(`/calls/peer/${userId}?limit=${limit}`);
    const data = Array.isArray(res) ? res : [];
    peerCallsCache.set(key, { ts: Date.now(), data });
    return data;
  } catch (err) {
    if (cached) return cached.data;
    throw err;
  }
}

export function createPairingSession(body) { return post('/pairing/sessions', body); }
export function getPairingSession(id) { return request('GET', `/pairing/sessions/${id}`); }
export function uploadPairingHistory(id, body) { return request('PUT', `/pairing/sessions/${id}/history`, body); }

/* ── Groups ── */

export function createGroup({ name, member_user_ids }) { return post('/groups', { name, member_user_ids }); }
export function listGroups() { return get('/groups'); }
export function getGroup(groupId) { return get(`/groups/${groupId}`); }
export function renameGroup(groupId, name) { return patch(`/groups/${groupId}`, { name }); }
export function deleteGroup(groupId) { return del(`/groups/${groupId}`); }

export async function uploadGroupAvatar(groupId, file) {
  const formData = new FormData();
  formData.append('avatar', file);
  const token = getToken();
  /** @type {Record<string, string>} */
  const headers = {};
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(`${getBaseUrl()}/groups/${groupId}/avatar`, {
    method: 'PUT',
    headers,
    body: formData
  });

  if (!res.ok) {
    const text = await res.text();
    let msg = text;
    try {
      const data = JSON.parse(text);
      if (data && data.error) msg = data.error;
    } catch {}
    throw new ApiError(msg || 'Не удалось загрузить аватар группы', res.status);
  }
  window.dispatchEvent(new Event('penik:rest-success'));
  return true;
}

export function listGroupMembers(groupId) { return get(`/groups/${groupId}/members`); }
export function inviteGroupMember(groupId, userId) { return post(`/groups/${groupId}/members`, { user_id: userId }); }
export function removeGroupMember(groupId, userId) { return del(`/groups/${groupId}/members/${userId}`); }
export function changeGroupMemberRole(groupId, userId, role) { return patch(`/groups/${groupId}/members/${userId}`, { role }); }
export function acceptGroupInvitation(groupId) { return post(`/groups/${groupId}/accept`); }
export function declineGroupInvitation(groupId) { return post(`/groups/${groupId}/decline`); }

export function listGroupKeyVersions(groupId) { return get(`/groups/${groupId}/keys`); }
export function getGroupEnvelope(groupId, version) { return get(`/groups/${groupId}/keys/${version}`); }
export function uploadGroupEnvelopes(groupId, version, envelopes) { return post(`/groups/${groupId}/keys/${version}/envelopes`, { envelopes }); }
export function rotateGroupKey(groupId) { return post(`/groups/${groupId}/keys/rotate`); }

/**
 * @param {number} groupId
 * @param {{ limit?: number, before_id?: number }} [opts]
 */
export function getGroupHistory(groupId, { limit = 100, before_id } = {}) {
  let path = `/groups/${groupId}/messages/history?limit=${limit}`;
  if (before_id) path += `&before_id=${before_id}`;
  return get(path);
}

export function uploadGroupHistoryPackets(groupId, packets) { return post(`/groups/${groupId}/history-packets`, { packets }); }
export function getGroupHistoryPacket(groupId) { return get(`/groups/${groupId}/history-packets`); }

// Stickers API
export function getMyStickers() { return get('/stickers/my'); }
export function getStickerPack(id) { return get(`/stickers/pack/${encodeURIComponent(id)}`); }
export function installStickerPack(id) { return post(`/stickers/pack/${encodeURIComponent(id)}/install`); }
export function uninstallStickerPack(id) { return del(`/stickers/pack/${encodeURIComponent(id)}/install`); }
export function importTelegramStickerPack(url) { return post('/stickers/import/telegram', { url }); }

let _serverTimeOffsetMs = 0;
let _timeSynced = false;

export async function syncServerTime() {
  try {
    const t0 = Date.now();
    let data;
    if (isDesktop() && window.go?.main?.App?.HttpRequest) {
      const resp = await desktopFetch('GET', `${getBaseUrl()}/time`);
      if (!resp || resp.status !== 200) return;
      data = JSON.parse(resp.body);
    } else {
      const res = await fetch(`${getBaseUrl()}/time`);
      if (!res.ok) return;
      data = await res.json();
    }
    const t1 = Date.now();
    const latency = Math.round((t1 - t0) / 2);
    const serverTimeMs = data.server_time_ms || (data.server_time * 1000);
    _serverTimeOffsetMs = (serverTimeMs + latency) - t1;
    _timeSynced = true;
  } catch (e) {
    console.warn('[TimeSync] Failed to sync server time:', e);
  }
}

export function getServerTimeMs() {
  return Date.now() + _serverTimeOffsetMs;
}

export function getServerTimeSec() {
  return Math.floor(getServerTimeMs() / 1000);
}


