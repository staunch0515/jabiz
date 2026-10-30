// The maintenance page (docs/quizbuks/plans/Q55-maintenance-page.md): one self-contained HTML file that Caddy serves for
// every address while the application is down or the maintenance lock is set. Nothing may be loaded from the
// application then, so the brand's mark, icon and colours, the illustration and the scripts are all inline.
//
// It shows one language, chosen in the browser: the link's ?lang=, else the language chosen in the app on this browser,
// else the time zone's (Japan → ja; mainland China, Hong Kong, Macao, Taiwan → zh), else the browser's, else English.
// Every 10 seconds it asks for the address that was opened again and, once that is no longer 503, loads it.
import { LANGUAGE_NAMES, LANGUAGES, MESSAGES } from './messages.mjs'

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }

export function esc(text) {
  return String(text).replace(/[&<>"']/g, (character) => ESCAPES[character])
}

/** A message with {name} and {s} filled in (s stays a slot the script fills). */
function text(language, key, name) {
  return esc(MESSAGES[language][key]).replace('{name}', esc(name)).replace('{s}', '<span class="s">10</span>')
}

/** The time zones whose language the page takes before the browser's (as the app guesses a country, country.ts). */
export const ZONES = {
  ja: ['Asia/Tokyo'],
  zh: ['Asia/Shanghai', 'Asia/Chongqing', 'Asia/Harbin', 'Asia/Urumqi', 'Asia/Kashgar', 'Asia/Hong_Kong', 'Asia/Macau',
    'Asia/Taipei'],
}

/** How often the page asks again, in milliseconds (a test may set window.__qbMaintenanceRetryMs before it loads). */
export const RETRY_MS = 10_000

// Decorative (aria-hidden): a screen being serviced, its gears turning, a leaf and coins in the brand's tokens.
const ART = `<svg class="art" viewBox="0 0 320 240" aria-hidden="true" focusable="false">
<ellipse cx="160" cy="214" rx="132" ry="16" fill="var(--sky)"/>
<rect x="58" y="40" width="204" height="138" rx="16" fill="var(--card)" stroke="var(--line)" stroke-width="3"/>
<rect x="74" y="56" width="172" height="106" rx="8" fill="var(--sky)"/>
<rect x="136" y="178" width="48" height="18" fill="var(--line)"/><rect x="110" y="194" width="100" height="8" rx="4" fill="var(--line)"/>
<g class="gear g1"><circle cx="134" cy="108" r="26" fill="none" stroke="var(--accent)" stroke-width="10" stroke-dasharray="10 7"/><circle cx="134" cy="108" r="14" fill="var(--accent)"/><circle cx="134" cy="108" r="6" fill="var(--sky)"/></g>
<g class="gear g2"><circle cx="186" cy="94" r="17" fill="none" stroke="var(--leaf-deep)" stroke-width="8" stroke-dasharray="7 5"/><circle cx="186" cy="94" r="9" fill="var(--leaf-deep)"/><circle cx="186" cy="94" r="4" fill="var(--sky)"/></g>
<path d="M206 126l22 22" stroke="var(--ink)" stroke-width="7" stroke-linecap="round"/><circle cx="200" cy="120" r="10" fill="none" stroke="var(--ink)" stroke-width="6"/>
<path d="M30 200c8-26 30-34 46-30-4 20-20 34-46 30z" fill="var(--leaf)"/><path d="M34 196c12-12 24-20 38-24" stroke="var(--leaf-deep)" stroke-width="2.5" fill="none" stroke-linecap="round"/>
<circle cx="276" cy="56" r="16" fill="var(--coin)"/><circle cx="276" cy="56" r="11" fill="none" stroke="var(--coin-deep)" stroke-width="2.5"/>
<circle cx="292" cy="190" r="9" fill="var(--coin)"/><circle cx="40" cy="70" r="6" fill="var(--coin-deep)"/>
</svg>`

const CSS = `
:root { color-scheme: light dark; --bg: #ffffff; --fg: #0f172a; --muted: #475569; --line: #e2e8f0; --card: #ffffff; }
@media (prefers-color-scheme: dark) {
  :root { --bg: #0b1220; --fg: #f1f5f9; --muted: #cbd5e1; --line: #27354d; --card: #111a2e; }
}
* { box-sizing: border-box; }
html, body { margin: 0; min-height: 100%; }
body {
  min-height: 100vh; display: flex; flex-direction: column; color: var(--fg); line-height: 1.65;
  background: radial-gradient(1200px 600px at 85% -10%, var(--sky), transparent 70%),
    radial-gradient(900px 500px at -10% 110%, var(--bg-soft), transparent 70%), var(--bg);
  font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", "Hiragino Sans", "Hiragino Kaku Gothic ProN",
    "Noto Sans CJK JP", "Noto Sans CJK SC", "PingFang SC", "Microsoft YaHei", sans-serif;
}
header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 1.25rem clamp(1rem, 4vw, 3rem); }
.brand { display: flex; align-items: center; gap: .6rem; font-weight: 750; font-size: 1.25rem; letter-spacing: -.01em; }
.brand svg { width: 2.25rem; height: 2.25rem; flex: none; }
.langs { display: flex; gap: .25rem; }
.langs button {
  font: inherit; font-size: .85rem; color: var(--muted); background: transparent; border: 1px solid transparent;
  border-radius: 999px; padding: .2rem .7rem; cursor: pointer;
}
.langs button[aria-pressed="true"] { color: var(--fg); border-color: var(--line); background: var(--card); }
main {
  flex: 1; display: grid; align-items: center; gap: clamp(1.5rem, 5vw, 4rem); width: min(72rem, 100%);
  margin: 0 auto; padding: clamp(1rem, 4vw, 3rem); grid-template-columns: minmax(0, 1fr);
}
@media (min-width: 52rem) { main { grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr); } }
.art { width: 100%; max-width: 30rem; height: auto; justify-self: center; }
.copy { max-width: 34rem; }
.eyebrow {
  display: inline-flex; align-items: center; gap: .5rem; font-size: .85rem; font-weight: 600; color: var(--chip-fg);
  background: var(--accent-soft); border-radius: 999px; padding: .25rem .8rem; margin-bottom: 1rem;
}
.eyebrow::before { content: ""; width: .5rem; height: .5rem; border-radius: 50%; background: var(--accent); animation: pulse 1.6s ease-in-out infinite; }
h1 { font-size: clamp(2rem, 5vw, 3.25rem); line-height: 1.15; letter-spacing: -.02em; margin: 0 0 1rem; }
.lead { font-size: clamp(1.05rem, 2vw, 1.2rem); margin: 0 0 .75rem; }
.auto { color: var(--muted); margin: 0 0 1.75rem; }
.status { display: flex; flex-wrap: wrap; align-items: center; gap: .75rem 1rem; }
.status output { color: var(--muted); font-variant-numeric: tabular-nums; }
.status button {
  font: inherit; font-weight: 650; color: var(--accent-fg); background: var(--accent); border: 0; border-radius: 999px;
  padding: .7rem 1.4rem; cursor: pointer;
}
.status button:focus-visible, .langs button:focus-visible { outline: 3px solid var(--accent); outline-offset: 3px; }
footer { text-align: center; color: var(--muted); font-size: .8rem; padding: 1rem; }
.gear { transform-box: fill-box; transform-origin: center; }
.g1 { animation: spin 8s linear infinite; }
.g2 { animation: spin 6s linear infinite reverse; }
@keyframes spin { to { transform: rotate(360deg); } }
@keyframes pulse { 50% { opacity: .35; } }
@media (prefers-reduced-motion: reduce) { .g1, .g2, .eyebrow::before { animation: none; } }
/* One language: with the script, the panels of the others are hidden (without it, all are shown). */
${LANGUAGES.map((language) => `html[data-lang="${language}"] [data-l]:not([data-l="${language}"])`).join(',\n')} { display: none; }
`

/** Chooses the language before the page is painted, and sets the document's lang and title. */
function chooser(name) {
  const titles = Object.fromEntries(LANGUAGES.map((language) =>
    [language, MESSAGES[language].title.replace('{name}', name)]))
  const tags = Object.fromEntries(LANGUAGES.map((language) => [language, MESSAGES[language].lang]))
  return `(function () {
  var LANGS = ${JSON.stringify(LANGUAGES)}, ZONES = ${JSON.stringify(ZONES)}, TITLES = ${JSON.stringify(titles)}, TAGS = ${JSON.stringify(tags)};
  function known(l) { return l && LANGS.indexOf(l) >= 0 ? l : null; }
  function pick() {
    var l = null;
    try { l = known(new URLSearchParams(location.search).get('lang')); } catch (e) {}
    if (l) return l;
    try { l = known(localStorage.getItem('jabiz.language')); } catch (e) {}
    if (l) return l;
    try {
      var zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      for (var k in ZONES) if (ZONES[k].indexOf(zone) >= 0) return k;
    } catch (e) {}
    var list = navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || ''];
    for (var i = 0; i < list.length; i++) { l = known(String(list[i]).slice(0, 2).toLowerCase()); if (l) return l; }
    return 'en';
  }
  window.__qbSetLanguage = function (l) {
    var root = document.documentElement;
    root.setAttribute('data-lang', l);
    root.setAttribute('lang', TAGS[l]);
    document.title = TITLES[l];
    var buttons = document.querySelectorAll('[data-set-lang]');
    for (var i = 0; i < buttons.length; i++) buttons[i].setAttribute('aria-pressed', String(buttons[i].getAttribute('data-set-lang') === l));
  };
  window.__qbSetLanguage(pick());
})();`
}

/** Asks for the opened address every RETRY_MS and loads it once the answer is not the maintenance page's 503. */
function retrier() {
  return `(function () {
  var every = window.__qbMaintenanceRetryMs || ${RETRY_MS}, due = Date.now() + every, busy = false, timer;
  function show(state, seconds) {
    var outputs = document.querySelectorAll('[data-status]');
    for (var i = 0; i < outputs.length; i++) {
      var o = outputs[i], t = o.getAttribute('data-' + state);
      o.innerHTML = state === 'next' ? t.replace('{s}', '<span class="s">' + seconds + '</span>') : t;
    }
  }
  function schedule() { due = Date.now() + every; tick(); }
  function tick() {
    clearTimeout(timer);
    var left = Math.max(0, Math.ceil((due - Date.now()) / 1000));
    if (left === 0) { check(); return; }
    show('next', left);
    timer = setTimeout(tick, Math.min(1000, due - Date.now()));
  }
  function check() {
    if (busy) return;
    busy = true; clearTimeout(timer); show('checking');
    fetch(location.href, { cache: 'no-store', redirect: 'manual', credentials: 'same-origin' }).then(function (r) {
      busy = false;
      // Back: the application answers (a page, a redirect, even a 404); 5xx is still down (503 the maintenance page).
      if (r.type === 'opaqueredirect' || (r.status > 0 && r.status < 500)) { show('back'); location.reload(); return; }
      schedule();
    }, function () { busy = false; schedule(); });
  }
  // The language was chosen before the switch existed: mark its button now.
  window.__qbSetLanguage(document.documentElement.getAttribute('data-lang'));
  var buttons = document.querySelectorAll('[data-check]');
  for (var i = 0; i < buttons.length; i++) buttons[i].addEventListener('click', check);
  var langs = document.querySelectorAll('[data-set-lang]');
  for (var j = 0; j < langs.length; j++) langs[j].addEventListener('click', function (e) {
    window.__qbSetLanguage(e.currentTarget.getAttribute('data-set-lang'));
  });
  document.addEventListener('visibilitychange', function () { if (!document.hidden) check(); });
  tick();
})();`
}

/**
 * The page for a brand: `brand` is the profile's public part (name), `mark` its mark.svg, `css` its site.css (the
 * static sites' brand tokens) and `icon` its 192 px icon (PNG bytes).
 */
export function renderPage({ brand, mark, css, icon }) {
  const name = brand.name
  const panels = LANGUAGES.map((language) => {
    const m = MESSAGES[language]
    const status = (key) => esc(m[key]).replace('{name}', esc(name))
    return `<div class="copy" data-l="${language}" lang="${m.lang}">
  <p class="eyebrow">${text(language, 'badge', name)}</p>
  <h1>${text(language, 'heading', name)}</h1>
  <p class="lead">${text(language, 'lead', name)}</p>
  <p class="auto">${text(language, 'auto', name)}</p>
  <div class="status">
    <button type="button" data-check>${esc(m.retry)}</button>
    <output data-status aria-live="polite" data-next="${status('next')}" data-checking="${status('checking')}" data-back="${status('back')}">${text(language, 'next', name)}</output>
  </div>
</div>`
  }).join('\n')
  const switcher = LANGUAGES.map((language) =>
    `<button type="button" data-set-lang="${language}" lang="${MESSAGES[language].lang}" aria-pressed="false">${esc(LANGUAGE_NAMES[language])}</button>`)
    .join('')
  const labels = LANGUAGES.map((language) => esc(MESSAGES[language].language)).join(' / ')
  return `<!doctype html>
<!-- Generated by quizbuks-web/maintenance/build.mjs from the brand profile (Q55); do not edit by hand. Served by Caddy
     while the application is unreachable or maintenance/maintenance.lock exists (deploy/quizbuks/DEPLOY.md "维护模式"). -->
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${esc(MESSAGES.en.title.replace('{name}', name))}</title>
<link rel="icon" type="image/png" href="data:image/png;base64,${icon.toString('base64')}">
<style>${css}
${CSS}</style>
<script>${chooser(name)}</script>
</head>
<body>
<header>
  <div class="brand">${mark.trim()}<span>${esc(name)}</span></div>
  <nav class="langs" aria-label="${labels}">${switcher}</nav>
</header>
<main>
${ART}
${panels}
</main>
<footer>© ${esc(name)}</footer>
<script>${retrier()}</script>
</body>
</html>
`
}
