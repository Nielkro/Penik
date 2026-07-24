const BASE = `${window.location.protocol}//${window.location.host}/api/v1`;

let _token = null;

export function setToken(t) {
  _token = t;
  if (t) localStorage.setItem('penik_token', t);
  else localStorage.removeItem('penik_token');
}

export function getToken() {
  if (!_token) _token = localStorage.getItem('penik_token');
  return _token;
}

async function request(method, path, body, opts = {}) {
  const token = opts.token ?? getToken();
  const headers = {};

  if (token) headers['Authorization'] = `Bearer ${token}`;

  let init = { method, headers };

  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }

  let res;
  try {
    res = await fetch(BASE + path, init);
  } catch (e) {
    const err = new Error('Нет соединения с сервером (CORS или сервер недоступен)');
    err.status = 0;
    throw err;
  }

  if (res.status === 204) return null;

  let data;
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) {
    data = await res.json();
  } else {
    const text = await res.text();
    // Some reverse proxies strip Content-Type from JSON responses. Parse
    // JSON-looking bodies anyway so callers still receive an object.
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!res.ok) {
    const msg = (typeof data === 'object' && data?.error) ? data.error
      : (typeof data === 'string' ? data : `HTTP ${res.status}`);
    const err = new Error(msg);
    err.status = res.status;
    throw err;
  }

  return data;
}

const get = (path, opts) => request('GET', path, undefined, opts);
const post = (path, body, opts) => request('POST', path, body, opts);
const patch = (path, body, opts) => request('PATCH', path, body, opts);
const del = (path, opts) => request('DELETE', path, undefined, opts);

export const apiPost = post;
export const apiGet  = get;
export const apiPatch = patch;
export const apiDelete = del;

/* ── Auth ── */

export async function register({ username, password, name, ik_pub, spk_pub, spk_sig, opk_pubs }) {
  return post('/register', { username, password, name, ik_pub, spk_pub, spk_sig, opk_pubs });
}

export async function login({ username, password }) {
  return post('/login', { username, password });
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
  return patch('/users/me', { name });
}

export async function uploadAvatar(file) {
  const formData = new FormData();
  formData.append('avatar', file);
  const token = getToken();
  const headers = {};
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(`${BASE}/avatar`, {
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
    const err = new Error(msg || 'Не удалось загрузить аватар');
    err.status = res.status;
    throw err;
  }
  return true;
}

export async function searchUsers(query) {
  const encoded = encodeURIComponent(query);
  return get(`/users/search?q=${encoded}`);
}

export async function getUserById(userId) {
  return get(`/users/${userId}`);
}

/* ── Messages (REST fallback) ── */

export async function getMessageHistory(userId, before, limit = 40) {
  let path = `/messages/${userId}?limit=${limit}`;
  if (before) path += `&before=${before}`;
  return get(path);
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

export function getGroupHistory(groupId, { limit = 100, before_id } = {}) {
  let path = `/groups/${groupId}/messages/history?limit=${limit}`;
  if (before_id) path += `&before_id=${before_id}`;
  return get(path);
}

export function uploadGroupHistoryPackets(groupId, packets) { return post(`/groups/${groupId}/history-packets`, { packets }); }
export function getGroupHistoryPacket(groupId) { return get(`/groups/${groupId}/history-packets`); }
