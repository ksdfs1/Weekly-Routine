"""Builds demo/index.html from ../index.html.

The demo must always be an empty shell: built-in example data only, no backend, nothing saved,
nothing personal. Run this after changing index.html:  python demo/build-demo.py

What changes compared with the real app:
- no server address (API_BASE = ""), so nothing is ever read from or written to the real routine
- its own localStorage keys, so it never touches the real app's cache/token in the same browser
- no PWA tags and no service worker; a "데모 페이지" banner on the normal page
- with ?app=1 (used by app.html) a stand-in for the Android app's WRNative bridge, so the
  "⚙ 알림" settings panel can be tried; it only keeps settings in memory
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', 'index.html')
OUT = os.path.join(HERE, 'index.html')

s = io.open(SRC, encoding='utf-8').read()


def sub(pattern, repl, count=1, flags=0):
    """regex replace that fails loudly if index.html no longer matches"""
    global s
    s, n = re.subn(pattern, repl, s, count=count, flags=flags)
    if n == 0:
        sys.exit('build-demo: pattern not found: ' + pattern)


# <head>: no PWA (manifest, apple-* meta), favicon from the parent folder
sub(r'<link rel="manifest"[^\n]*\n<link rel="icon"[^\n]*\n<link rel="apple-touch-icon"[^\n]*\n'
    r'(?:<meta name="(?:apple-mobile-web-app-[^"]*|mobile-web-app-capable)"[^\n]*\n)+',
    '<link rel="icon" type="image/svg+xml" href="../icons/icon.svg">\n')

# app.html's stand-in for the Android bridge (only with ?app=1); keeps everything in memory
FAKE_BRIDGE = r'''<script>
/* demo only: pretends to be the Android app's WRNative bridge so the "⚙ 알림" panel works.
   Nothing leaves this page; settings live in memory until the page is closed. */
(function(){
  if (!/[?&]app=1(&|$)/.test(location.search)) return;
  var settings = {notify:true, sound:true, vibrate:true, notifyGaps:true};
  var bridge = {
    onmessage: null,
    postMessage: function(raw){
      var m = JSON.parse(raw), a = m.args || {}, r = true;
      switch (m.op){
        case 'getSettings': r = settings; break;
        case 'setSetting': settings[a.key] = !!a.value; break;
        case 'getPermissions': r = {notifications:true, exactAlarm:true, battery:false}; break;
        case 'getInfo': r = {lastSync: Date.now(), version: '데모'}; break;
        case 'getToken': r = ''; break;
        case 'sendTestNotification':
          r = {ok:true};
          try{ parent.postMessage({demoNotification: true}, '*'); }catch(e){}
          break;
        case 'saveFile':
          var url = URL.createObjectURL(new Blob([a.content], {type:'application/json'}));
          var link = document.createElement('a'); link.href = url; link.download = a.name;
          document.body.appendChild(link); link.click(); link.remove();
          setTimeout(function(){ URL.revokeObjectURL(url); }, 1500);
          r = {ok:true};
          break;
      }
      setTimeout(function(){ if (bridge.onmessage) bridge.onmessage({data: JSON.stringify({id:m.id, result:r})}); }, 30);
    }
  };
  window.WRNative = bridge;
})();
</script>
</head>'''
sub(r'</head>', FAKE_BRIDGE)

# banner on the normal demo page (not inside the widget view or the phone frame)
sub(r'(\.toast\.show\{[^\n]*\n)',
    r'\1  .demo-banner{ background:#182545; color:#eef1fb; font-size:.78rem; text-align:center; padding:9px 12px; border-bottom:1px solid #28365a; font-family:"IBM Plex Sans",system-ui,sans-serif; }\n'
    r'  html.widget .demo-banner, html.native .demo-banner{ display:none; }\n')
sub(r'<body>\n', '<body>\n<div class="demo-banner">🧪 데모 페이지입니다 — 예시 데이터만 보여주며, 이 화면에서의 변경사항은 어디에도 저장되지 않습니다.</div>\n')

# no backend, separate storage
sub(r'  // Filled in once the Cloudflare Worker is deployed[^\n]*\n  var API_BASE = "[^"]*";\n',
    '  // Demo build (demo/build-demo.py): intentionally has no backend, so it only ever shows the\n'
    '  // built-in example data and never reads/writes any real account\'s routine.\n'
    '  var API_BASE = "";\n')
sub(r"var TOKEN_KEY = 'weekly-routine-write-token';", "var TOKEN_KEY = 'weekly-routine-DEMO-write-token';")
sub(r"var LS_CACHE_KEY = 'weekly-routine-cache-v1';", "var LS_CACHE_KEY = 'weekly-routine-DEMO-cache-v1';")
sub(r"var CASE_OVERRIDES_KEY = 'weekly-routine-case-overrides';", "var CASE_OVERRIDES_KEY = 'weekly-routine-DEMO-case-overrides';")
sub(r"toast\('아직 서버 주소가 설정되지 않았어요\.'\)", "toast('데모 페이지라 서버에 저장하거나 불러오지 않아요.')", count=0)

# no service worker (it would cache the demo as the app)
sub(r"\n  /\* offline support \+ home-screen install[^\n]*\n  if \('serviceWorker' in navigator\)\{\n.*?\n  \}\n", '\n', flags=re.S)

# last line of defence: nothing that points at the real backend may end up in the demo
for leak in ('workers.dev', 'weekly-routine-write-token\'', 'weekly-routine-cache-v1\''):
    if leak in s:
        sys.exit('build-demo: demo still contains ' + leak)

io.open(OUT, 'w', encoding='utf-8', newline='').write(s)
print('demo/index.html rebuilt')
