/*
 * The Do Zone service worker — offline app shell.
 *
 * The SW manages ONLY this app's own origin (its shell). It NEVER intercepts cross-origin requests
 * (Dropbox, Google Drive, GIS, fonts, images, …) — those go straight to the browser, so the SW can
 * never sit in the path of normal third-party traffic.
 *
 * Strategy:
 *   - cross-origin           -> not intercepted; the browser handles it
 *   - navigations            -> network-first, fall back to cached index.html (offline)
 *   - same-origin GET assets -> network-first, fall back to cache (offline). main.bundle.js has a
 *                               STABLE filename, so network-first means a redeploy is live immediately
 *                               and the cache is only the offline fallback.
 *
 * Bump CACHE_VERSION when the shell list or strategy changes; activate() drops older caches.
 */
const CACHE_VERSION = 'tdz-shell-v3';

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

  // HARD RULE: the SW only ever manages this app's own origin. Every cross-origin request — Dropbox,
  // Google Drive, GIS, fonts, images, anything — is left entirely to the browser. The SW must never
  // sit in the path of normal third-party traffic.
  if (new URL(req.url).origin !== self.location.origin) return;

  // Navigations: network-first so a redeploy is served immediately; offline -> cached shell.
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE_VERSION).then((c) => c.put('/index.html', copy)).catch(() => {});
          return res;
        })
        .catch(() => caches.match('/index.html'))
    );
    return;
  }

  // Same-origin assets: network-first (a redeploy is live at once); cache only as the offline fallback.
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
});
