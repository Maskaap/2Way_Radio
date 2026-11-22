// Minimal service worker: makes the web app installable ("Add to Home Screen").
// Always fetches from the network first so updates show immediately; falls back to cache when offline.
const CACHE = "ptt-app-v1";
const SHELL = ["/app/", "/app/ptt_h.png", "/app/icon-192.png", "/logo.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET" || new URL(req.url).origin !== location.origin || req.url.includes("/api/")) return;
  e.respondWith(
    fetch(req).then((res) => {
      if (res.ok && SHELL.some((p) => req.url.endsWith(p))) {
        const copy = res.clone();   // clone before the page consumes the body
        caches.open(CACHE).then((c) => c.put(req, copy));
      }
      return res;
    }).catch(() => caches.match(req))
  );
});
