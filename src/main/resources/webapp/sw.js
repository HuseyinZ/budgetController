// BudgetController PWA — Service Worker
// Strateji: network-first (fresh data önemli), offline fallback için cache
const CACHE_NAME = 'budget-pos-v1';
const STATIC_ASSETS = [
  '/app/index.html',
  '/app/style.css',
  '/app/app.js',
  '/app/manifest.json'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.addAll(STATIC_ASSETS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then(names =>
      Promise.all(names.filter(n => n !== CACHE_NAME).map(n => caches.delete(n)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  // API çağrıları → network-only (cache yapma, eski veri gösterme)
  if (req.url.includes('/api/')) {
    return;  // browser default network handling
  }
  // Statik dosyalar → network-first, fallback cache
  event.respondWith(
    fetch(req)
      .then(resp => {
        const copy = resp.clone();
        caches.open(CACHE_NAME).then(c => c.put(req, copy)).catch(() => {});
        return resp;
      })
      .catch(() => caches.match(req).then(r => r || caches.match('/app/index.html')))
  );
});
