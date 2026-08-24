/* ── Service Worker Streamer for Encrypted Media (HTTP 206 Partial Content) ── */

/// <reference lib="webworker" />

// `self` is a ServiceWorkerGlobalScope here, but the default lib types it as the
// broader WorkerGlobalScope. Narrowing it once gives correct event types below.
const sw = /** @type {ServiceWorkerGlobalScope} */ (/** @type {unknown} */ (self));

// Replaced at build time by scripts/build-sw.js so each release ships a worker
// with different bytes; browsers reinstall a worker only when its bytes change.
const SW_VERSION = '__SW_VERSION__';

const STICKER_CACHE = 'penik-stickers-v1';

sw.addEventListener('install', () => {
  console.log(`[sw] installing ${SW_VERSION}`);
  sw.skipWaiting();
});

sw.addEventListener('activate', (event) => {
  event.waitUntil(sw.clients.claim());
});

sw.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Cache-First for sticker static assets (instant load, 0 network requests)
  if (url.pathname.startsWith('/api/v1/stickers/file/')) {
    event.respondWith(
      caches.open(STICKER_CACHE).then(async (cache) => {
        const cached = await cache.match(event.request);
        if (cached) {
          return cached;
        }
        try {
          const networkResponse = await fetch(event.request);
          if (networkResponse && networkResponse.ok) {
            cache.put(event.request, networkResponse.clone());
          }
          return networkResponse;
        } catch (err) {
          if (cached) return cached;
          throw err;
        }
      })
    );
    return;
  }

  // Intercept stream requests registered under /sw-stream/
  if (url.pathname.startsWith('/sw-stream/')) {
    event.respondWith(handleStreamRequest(event.request));
  }
});

async function handleStreamRequest(request) {
  const url = new URL(request.url);
  const mediaId = url.pathname.replace('/sw-stream/', '');

  // Communicate with clients to retrieve blob from memory/IndexedDB cache
  const clientList = await sw.clients.matchAll({ type: 'window', includeUncontrolled: true });
  if (!clientList || clientList.length === 0) {
    return new Response('No client available', { status: 503 });
  }

  // Request media data from active window client
  const mediaData = await new Promise((resolve) => {
    const channel = new MessageChannel();
    channel.port1.onmessage = (event) => resolve(event.data);
    clientList[0].postMessage({ type: 'GET_STREAM_DATA', mediaId }, [channel.port2]);
    setTimeout(() => resolve(null), 3000);
  });

  if (!mediaData || !mediaData.blob) {
    return new Response('Media not ready', { status: 404 });
  }

  const blob = mediaData.blob;
  const mime = mediaData.mime || 'video/mp4';
  const size = blob.size;

  const rangeHeader = request.headers.get('Range');
  if (!rangeHeader) {
    return new Response(blob, {
      status: 200,
      headers: {
        'Content-Type': mime,
        'Content-Length': size.toString(),
        'Accept-Ranges': 'bytes',
      },
    });
  }

  // Parse Range header e.g. "bytes=0-1048575" or "bytes=524288-"
  const match = /bytes=(\d+)-(\d+)?/.exec(rangeHeader);
  if (!match) {
    return new Response('Invalid Range', { status: 416 });
  }

  const start = parseInt(match[1], 10);
  let end = match[2] ? parseInt(match[2], 10) : size - 1;
  if (end >= size) end = size - 1;

  if (start >= size || start > end) {
    return new Response('Requested Range Not Satisfiable', {
      status: 416,
      headers: {
        'Content-Range': `bytes */${size}`,
      },
    });
  }

  const chunk = blob.slice(start, end + 1);
  const chunkSize = end - start + 1;

  return new Response(chunk, {
    status: 206,
    headers: {
      'Content-Type': mime,
      'Content-Length': chunkSize.toString(),
      'Content-Range': `bytes ${start}-${end}/${size}`,
      'Accept-Ranges': 'bytes',
    },
  });
}
