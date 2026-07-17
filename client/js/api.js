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
    data = await res.text();
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
