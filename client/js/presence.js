// Live presence pub/sub. app.js feeds this from the PRESENCE_UPDATE websocket
// opcode; UI code (chat header, group member profile) subscribes to update
// instantly instead of waiting on the periodic REST poll.
const _listeners = new Map(); // userId (string) -> Set(fn)

export function onPresenceUpdate(userId, fn) {
  const key = String(userId);
  if (!_listeners.has(key)) _listeners.set(key, new Set());
  _listeners.get(key).add(fn);
  return () => _listeners.get(key)?.delete(fn);
}

export function emitPresenceUpdate(userId, presence) {
  const set = _listeners.get(String(userId));
  if (!set) return;
  for (const fn of set) {
    try { fn(presence); } catch (e) { console.error('[presence] listener error', e); }
  }
}
