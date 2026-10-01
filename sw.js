// Weekly Routine — service worker for the installable (PWA) version.
// Pages are network-first so edits pushed to GitHub Pages show up right away;
// icons/manifest are cache-first. Requests to other origins (the Cloudflare
// Worker API) are left alone — routine data is cached in localStorage by index.html.

var CACHE_VERSION = 'weekly-routine-v2';
var APP_SHELL = [
  './',
  'index.html',
  'manifest.webmanifest',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/icon-maskable-512.png',
  'icons/apple-touch-icon.png'
];

self.addEventListener('install', function(event){
  event.waitUntil(
    caches.open(CACHE_VERSION).then(function(cache){ return cache.addAll(APP_SHELL); })
      .then(function(){ return self.skipWaiting(); })
  );
});

self.addEventListener('activate', function(event){
  event.waitUntil(
    caches.keys().then(function(keys){
      return Promise.all(keys.filter(function(k){ return k !== CACHE_VERSION; })
        .map(function(k){ return caches.delete(k); }));
    }).then(function(){ return self.clients.claim(); })
  );
});

self.addEventListener('fetch', function(event){
  var req = event.request;
  if (req.method !== 'GET') return;
  var url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  if (req.mode === 'navigate' || url.pathname.endsWith('.html')){
    // one cached copy per page, whatever the query (?view=widget, ?app=1 … are the same file)
    var key = url.origin + url.pathname;
    event.respondWith(
      fetch(req).then(function(res){
        if (res.ok){
          var copy = res.clone();
          caches.open(CACHE_VERSION).then(function(cache){ cache.put(key, copy); });
        }
        return res;
      }).catch(function(){
        return caches.match(key).then(function(hit){
          if (hit) return hit;
          // the demo page is a separate app; don't fall back to the real one for it
          if (url.pathname.indexOf('/demo/') !== -1) return Response.error();
          return caches.match('index.html');
        });
      })
    );
    return;
  }

  event.respondWith(
    caches.match(req).then(function(hit){ return hit || fetch(req); })
  );
});
