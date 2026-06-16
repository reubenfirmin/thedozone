// PWA: stash the install prompt (it fires BEFORE the Kotlin bundle boots) for the in-app
// "Install app" menu item, and register the offline service worker. Both are best-effort / no-op
// where unsupported.
//
// This lives in its own same-origin file (not inline in index.html) so the page's CSP can use
// `script-src 'self'` with NO `'unsafe-inline'`. It must stay loaded EARLY and WITHOUT defer/async
// (a plain `<script src>` before the bundle) so it still wins the race against `beforeinstallprompt`.
window.__tdzDeferredInstall = null;
window.addEventListener('beforeinstallprompt', function (e) {
  e.preventDefault();
  window.__tdzDeferredInstall = e;
});
window.addEventListener('appinstalled', function () {
  window.__tdzDeferredInstall = null;
});
if ('serviceWorker' in navigator) {
  window.addEventListener('load', function () {
    navigator.serviceWorker.register('/sw.js').catch(function () {});
  });
}
