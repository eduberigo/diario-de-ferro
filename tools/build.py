import time, json
import os
D = os.path.dirname(os.path.abspath(__file__)) + '/../'
src = open(D + 'src/app.html', encoding='utf-8').read()
ver = time.strftime('%Y%m%d%H%M')
head = f'''<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content">
<meta name="theme-color" content="#12161b">
<link rel="manifest" href="manifest.json">
<link rel="icon" href="icon-192.png">
<link rel="apple-touch-icon" href="icon-192.png">
<style>:root{{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}}body{{margin:0}}[hidden]{{display:none!important}}img{{max-width:100%}}</style>
</head>
<body>
'''
tail = f'''
<script>
if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost')) navigator.serviceWorker.register('sw.js?v={ver}').catch(() => {{}});
</script>
</body>
</html>
'''
open(D + 'index.html', 'w', encoding='utf-8').write(head + src + tail)
json.dump({"name": "Diário de Ferro", "short_name": "Ferro", "start_url": "./", "scope": "./", "display": "standalone", "orientation": "portrait",
           "background_color": "#12161b", "theme_color": "#12161b", "lang": "pt-BR",
           "icons": [{"src": "icon-192.png", "sizes": "192x192", "type": "image/png"}, {"src": "icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any maskable"}]},
          open(D + 'manifest.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
open(D + 'sw.js', 'w').write(f'''const CACHE = 'ferro-{ver}';
const SHELL = ['./', './index.html', './manifest.json', './icon-192.png', './icon-512.png'];
self.addEventListener('install', e => {{ e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())); }});
self.addEventListener('activate', e => {{ e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())); }});
self.addEventListener('fetch', e => {{
  const r = e.request; if (r.method !== 'GET') return;
  const url = new URL(r.url);
  if (url.origin === location.origin) {{
    // app: usa o que está guardado; atualiza em segundo plano quando houver rede
    e.respondWith(caches.match(r, {{ignoreSearch: true}}).then(hit => {{
      const net = fetch(r).then(res => {{ if (res.ok) caches.open(CACHE).then(c => c.put(r, res.clone())); return res; }}).catch(() => hit);
      return hit || net;
    }}));
  }} else if (url.host.includes('fonts.g')) {{
    e.respondWith(caches.match(r).then(hit => hit || fetch(r).then(res => {{ caches.open(CACHE).then(c => c.put(r, res.clone())); return res; }}).catch(() => new Response('', {{status: 504}}))));
  }}
}});
''')
open(D + 'LEIA-ME.txt', 'w', encoding='utf-8').write('''Diário de Ferro — versão instalável (funciona sem internet)

1. Abra https://app.netlify.com/drop no computador ou no celular.
2. Arraste esta pasta inteira (ou escolha os arquivos) para a área indicada.
3. Crie a conta grátis quando o Netlify pedir, para o site não expirar.
4. Abra o endereço .netlify.app no Chrome do celular.
5. Menu do Chrome (⋮) > "Instalar app" ou "Adicionar à tela inicial".
6. Abra pelo ícone uma vez com internet. Depois funciona sem rede.

Para atualizar: arraste a pasta nova no mesmo site do Netlify (Deploys > arrastar).
Os dados ficam no celular. Faça backup em Ajustes > Dados > Baixar / compartilhar arquivo.
''')
print('built', ver)
