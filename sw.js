const CACHE = 'ferro-202610262053';
const SHELL = ['./', './index.html', './manifest.json', './icon-192.png', './icon-512.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', e => {
  const r = e.request; if (r.method !== 'GET') return;
  const url = new URL(r.url);
  if (url.origin === location.origin) {
    // app: usa o que está guardado; atualiza em segundo plano quando houver rede
    e.respondWith(caches.match(r, {ignoreSearch: true}).then(hit => {
      const net = fetch(r).then(res => { if (res.ok) caches.open(CACHE).then(c => c.put(r, res.clone())); return res; }).catch(() => hit);
      return hit || net;
    }));
  } else if (url.host.includes('fonts.g')) {
    e.respondWith(caches.match(r).then(hit => hit || fetch(r).then(res => { caches.open(CACHE).then(c => c.put(r, res.clone())); return res; }).catch(() => new Response('', {status: 504}))));
  }
});
