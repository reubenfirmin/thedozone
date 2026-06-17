/*
 * The Do Zone service worker — offline app shell.
 *
 * Strategy:
 *   - navigations            -> network-first, fall back to cached index.html (deploys stay live)
 *   - same-origin GET assets -> network-first, fall back to cache when offline. main.bundle.js has a
 *                               STABLE filename, so stale-while-revalidate served the OLD bundle for a
 *                               load after every deploy; network-first means a redeploy is live
 *                               immediately and the cache is only an offline fallback.
 *   - cross-origin GET       -> best-effort cache-first; failures pass through to the network
 *
 * Bump CACHE_VERSION when the shell list or strategy changes; activate() drops older caches.
 */
const CACHE_VERSION = 'tdz-shell-v2';

// Same-origin shell assets to pre-cache on install. The leading "/" and "/index.html" both resolve
// to the app HTML; keeping both lets the navigation fallback hit the cache either way.
const SHELL = [
  '/',
  '/index.html',
  '/main.bundle.js',
  '/style.css',
  '/site.webmanifest',
  '/IBM_VGA_8x16.woff',
  '/favicon.ico',
  '/favicon-16x16.png',
  '/favicon-32x32.png',
  '/apple-touch-icon.png',
  '/android-chrome-192x192.png',
  '/android-chrome-512x512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_VERSION)
      // addAll is atomic (one 404 fails the whole install); add individually, ignoring misses.
      .then((cache) => Promise.all(SHELL.map((url) => cache.add(url).catch(() => {}))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  // Navigations: network-first so a redeploy is served immediately; offline -> cached shell.
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE_VERSION).then((c) => c.put('/index.html', copy)).catch(() => {});
          return res;
        })
        .catch(() => caches.match(req).then((hit) => hit || caches.match('/index.html')))
    );
    return;
  }

  const url = new URL(req.url);

  if (url.origin === self.location.origin) {
    // Same-origin assets: network-first so a redeploy (stable-filename bundle) is live at once;
    // cache is refreshed on every success and used only as the offline fallback.
    event.respondWith(
      fetch(req)
        .then((res) => {
          if (res && res.ok) {
            const copy = res.clone();
            caches.open(CACHE_VERSION).then((c) => c.put(req, copy)).catch(() => {});
          }
          return res;
        })
        .catch(() => caches.open(CACHE_VERSION).then((c) => c.match(req)))
    );
    return;
  }

  // Cross-origin (Google Fonts, GIS): best-effort cache-first, never block the app on them.
  event.respondWith(
    caches.match(req).then((cached) => {
      if (cached) return cached;
      return fetch(req)
        .then((res) => {
          if (res && (res.ok || res.type === 'opaque')) {
            const copy = res.clone();
            caches.open(CACHE_VERSION).then((c) => c.put(req, copy)).catch(() => {});
          }
          return res;
        })
        .catch(() => cached);
    })
  );
});
