// Shared key-bundle cache: single source of truth for GET /keys/bundle/{id}.
// Deduplicates in-flight requests, rate-limits forceRefresh, and serves stale
// cache on errors so history sync cannot stampede the 60/min server limiter.

import { apiGet } from "./api.js";

const bundleMemoryCache = new Map(); // userId -> { bundle, expiresAt }
const bundleInflight = new Map(); // userId -> Promise<bundle>
const bundleForceCooldown = new Map(); // userId -> ts of last force refresh
const BUNDLE_FORCE_MIN_INTERVAL_MS = 10 * 1000;

export async function getCachedKeyBundle(userId, forceRefresh = false) {
  const key = String(userId);
  const now = Date.now();
  if (!forceRefresh && bundleMemoryCache.has(key)) {
    const cached = bundleMemoryCache.get(key);
    if (cached.expiresAt > now) {
      return cached.bundle;
    }
  }
  // Deduplicate concurrent fetches even for forceRefresh — history sync and
  // decrypt fallbacks used to stampede /keys/bundle and trip the limiter.
  if (bundleInflight.has(key)) {
    return bundleInflight.get(key);
  }
  if (forceRefresh && bundleMemoryCache.has(key)) {
    const lastForce = bundleForceCooldown.get(key) || 0;
    if (now - lastForce < BUNDLE_FORCE_MIN_INTERVAL_MS) {
      return bundleMemoryCache.get(key).bundle;
    }
    bundleForceCooldown.set(key, now);
  }
  const promise = (async () => {
    try {
      const bundle = await apiGet(`/keys/bundle/${userId}`);
      const myId = Number(localStorage.getItem("user_id"));
      const ttl = Number(userId) === myId ? 30 * 1000 : 5 * 60 * 1000;
      bundleMemoryCache.set(key, { bundle, expiresAt: Date.now() + ttl });
      return bundle;
    } catch (e) {
      const cached = bundleMemoryCache.get(key);
      if (cached) {
        cached.expiresAt = Date.now() + 15 * 1000;
        return cached.bundle;
      }
      throw e;
    } finally {
      bundleInflight.delete(key);
    }
  })();
  bundleInflight.set(key, promise);
  return promise;
}

export function prefetchKeyBundle(userId) {
  if (!userId) return;
  const myId = Number(localStorage.getItem("user_id"));
  getCachedKeyBundle(userId).catch(() => {});
  if (myId && myId !== Number(userId)) {
    getCachedKeyBundle(myId).catch(() => {});
  }
}
