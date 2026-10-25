// The one layout of the business site and the body of each page, as template functions over the language's content.
// Everything the content gives is escaped; the few places that take markup (a paragraph with a link) are rendered by
// the template itself. No inline scripts or styles: the platform's content security policy allows neither.

const PAGE_PATH = { home: '', merchants: 'merchants/', takers: 'takers/', surveys: 'surveys/', pricing: 'pricing/',
  contact: 'contact/' }
const LANGUAGE_NAMES = { en: 'English', zh: '中文', ja: '日本語' }
const LOCALES = { en: 'en', zh: 'zh-Hans', ja: 'ja' }

export function esc(text) {
  return String(text ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;',
    "'": '&#39;' })[c])
}

export function pagePath(language, page, base) {
  return `${base}${language === 'en' ? '' : `${language}/`}${PAGE_PATH[page]}`
}

const ICONS = {
  spark: '<path d="M12 2l1.8 5.5L19 9l-5.2 1.5L12 16l-1.8-5.5L5 9l5.2-1.5z"/><path d="M19 15l.9 2.6L22.5 18l-2.6.9L19 21.5l-.9-2.6L15.5 18l2.6-.9z"/>',
  target: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1.2" fill="currentColor"/>',
  gift: '<rect x="3" y="8" width="18" height="13" rx="2"/><path d="M12 8v13M3 13h18M12 8c-2.5 0-4.5-1.3-4.5-3S9 2.5 10 3c1.3.7 2 3 2 5zm0 0c2.5 0 4.5-1.3 4.5-3S15 2.5 14 3c-1.3.7-2 3-2 5z"/>',
  chart: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
  mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 7l9 6 9-6"/>',
  qr: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h3v3h-3zM19 14h2v2h-2zM14 19h2v2h-2zM18 18h3v3h-3z"/>',
  shield: '<path d="M12 2l8 3v6c0 5-3.5 9-8 11-4.5-2-8-6-8-11V5z"/><path d="M9 12l2 2 4-4"/>',
  phone: '<rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 18h2"/>',
  check: '<path d="M5 12l4 4L19 6"/>',
  users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0113 0"/><circle cx="17" cy="9" r="2.5"/><path d="M15.5 14.5a5 5 0 016 5.5"/>',
  clipboard: '<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 4V2.5h6V4M8.5 10h7M8.5 14h7M8.5 18h4"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
}

export function icon(name, className = 'icon') {
  return `<svg class="${className}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] ?? ''}</svg>`
}

function list(items, className = 'checks') {
  return `<ul class="${className}">${items.map((item) => `<li>${icon('check', 'icon small')}<span>${esc(item)}</span></li>`).join('')}</ul>`
}

function header({ language, page, content, base }) {
  const nav = Object.entries(content.nav).map(([key, label]) =>
    `<a href="${pagePath(language, key, base)}"${key === page ? ' aria-current="page"' : ''}>${esc(label)}</a>`).join('')
  const languages = ['en', 'zh', 'ja'].map((lang) => lang === language
    ? `<span aria-current="true">${LANGUAGE_NAMES[lang]}</span>`
    : `<a href="${pagePath(lang, page, base)}" lang="${LOCALES[lang]}" hreflang="${LOCALES[lang]}" data-lang="${lang}">${LANGUAGE_NAMES[lang]}</a>`).join('')
  return `<header class="top">
  <div class="wrap bar">
    <a class="brand" href="${pagePath(language, 'home', base)}"><img src="${base}icon-192.png" alt="" width="32" height="32" /><span>QuizBuks</span></a>
    <button class="menu-toggle" type="button" data-menu-toggle aria-expanded="false" aria-controls="site-menu" aria-label="${esc(content.menu)}"><span></span><span></span><span></span></button>
    <nav id="site-menu" class="menu" data-menu aria-label="${esc(content.navLabel)}">
      <div class="links">${nav}</div>
      <div class="lang" aria-label="${esc(content.languageLabel)}">${languages}</div>
      <div class="actions">
        <a class="button ghost" href="/taker/signup" data-signup="taker">${esc(content.cta.taker)}</a>
        <a class="button primary" href="/sponsor/signup" data-signup="sponsor" target="_blank" rel="noopener">${esc(content.cta.sponsor)}</a>
      </div>
    </nav>
  </div>
</header>`
}

function footer({ language, content, base }) {
  const f = content.footer
  return `<footer class="foot">
  <div class="wrap grid4">
    <div>
      <a class="brand" href="${pagePath(language, 'home', base)}"><img src="${base}icon-192.png" alt="" width="28" height="28" /><span>QuizBuks</span></a>
      <p class="muted">${esc(f.tagline)}</p>
    </div>
    <div><h3>${esc(f.product)}</h3><ul class="plain">
      <li><a href="${pagePath(language, 'merchants', base)}">${esc(content.nav.merchants)}</a></li>
      <li><a href="${pagePath(language, 'takers', base)}">${esc(content.nav.takers)}</a></li>
      <li><a href="${pagePath(language, 'surveys', base)}">${esc(content.nav.surveys)}</a></li>
      <li><a href="${pagePath(language, 'pricing', base)}">${esc(content.nav.pricing)}</a></li></ul></div>
    <div><h3>${esc(f.open)}</h3><ul class="plain">
      <li><a href="/taker/">${esc(f.app)}</a></li>
      <li><a href="/sponsor/">${esc(f.console)}</a></li>
      <li><a href="${pagePath(language, 'contact', base)}">${esc(content.nav.contact)}</a></li></ul></div>
    <div><h3>${esc(f.legal)}</h3><ul class="plain">
      <li><a href="/taker/legal/terms">${esc(f.terms)}</a></li>
      <li><a href="/taker/legal/sponsor-terms">${esc(f.sponsorTerms)}</a></li>
      <li><a href="/taker/legal/privacy">${esc(f.privacy)}</a></li></ul></div>
  </div>
  <div class="wrap fine"><span>© ${new Date().getUTCFullYear()} QuizBuks</span><span>${esc(f.rights)}</span></div>
</footer>`
}

function cards(items, cols = 'grid3') {
  return `<div class="${cols}">${items.map((c) => `<div class="card">${c.icon ? icon(c.icon, 'icon accent') : ''}<h3>${esc(c.title)}</h3><p>${esc(c.text)}</p></div>`).join('')}</div>`
}

function steps(items) {
  return `<ol class="steps">${items.map((s, i) => `<li><span class="num" aria-hidden="true">${i + 1}</span><div><h3>${esc(s.title)}</h3><p>${esc(s.text)}</p></div></li>`).join('')}</ol>`
}

function cta(content) {
  return `<div class="actions">
    <a class="button primary large" href="/taker/signup" data-signup="taker">${esc(content.cta.takerLong)}</a>
    <a class="button ghost large" href="/sponsor/signup" data-signup="sponsor" target="_blank" rel="noopener">${esc(content.cta.sponsorLong)}</a>
  </div>`
}

function tryIt({ language, content, tryQuizzes, base }) {
  const t = content.home.tryIt
  const blocks = ['en', 'zh', 'ja'].map((lang) => {
    const entry = tryQuizzes[lang] ?? { url: '', qr: '' }
    const ready = Boolean(entry.url)
    const qr = entry.qr ? `<img class="qr" src="${esc(entry.qr.startsWith('/') ? entry.qr : base + entry.qr)}" alt="${esc(t.qrAlt)} (${LANGUAGE_NAMES[lang]})" width="160" height="160" />`
      : `<div class="qr placeholder" role="img" aria-label="${esc(t.qrPending)}">${icon('qr', 'icon big muted')}<span>${esc(t.qrPending)}</span></div>`
    const button = ready
      ? `<a class="button primary" href="${esc(entry.url)}" lang="${LOCALES[lang]}">${esc(t.buttons[lang])}</a>`
      : `<span class="button primary disabled" aria-disabled="true" lang="${LOCALES[lang]}">${esc(t.buttons[lang])}</span><span class="soon">${esc(t.comingSoon)}</span>`
    return `<div class="try-card" data-try="${lang}">${qr}${button}</div>`
  }).join('')
  return `<section class="band" aria-labelledby="try">
  <div class="wrap">
    <p class="eyebrow">${esc(t.eyebrow)}</p>
    <h2 id="try">${esc(t.title)}</h2>
    <p class="lead">${esc(t.text)}</p>
    <div class="grid3 try">${blocks}</div>
    <p class="muted small">${esc(t.note)}</p>
  </div>
</section>`
}

function home(ctx) {
  const { content } = ctx
  const h = content.home
  return `<section class="hero">
  <div class="wrap hero-grid">
    <div>
      <p class="eyebrow">${esc(h.eyebrow)}</p>
      <h1>${esc(h.title)}</h1>
      <p class="lead">${esc(h.lead)}</p>
      ${cta(content)}
      <p class="muted small">${esc(h.heroNote)}</p>
    </div>
    <div class="mock" aria-hidden="true">
      <div class="phone">
        <div class="phone-bar"></div>
        <div class="phone-card"><span class="chip">${esc(h.mock.chip)}</span><strong>${esc(h.mock.question)}</strong>
          <div class="opt">${esc(h.mock.a)}</div><div class="opt selected">${esc(h.mock.b)}</div><div class="opt">${esc(h.mock.c)}</div>
          <div class="reward">${icon('gift', 'icon small')} ${esc(h.mock.reward)}</div></div>
      </div>
      <div class="stat-card"><span class="stat-label">${esc(h.mock.statLabel)}</span><span class="stat-value">${esc(h.mock.statValue)}</span><span class="bar"><i></i></span></div>
    </div>
  </div>
</section>
<section class="wrap section" aria-labelledby="value">
  <h2 id="value" class="center">${esc(h.value.title)}</h2>
  <p class="lead center">${esc(h.value.text)}</p>
  ${cards(h.value.cards)}
</section>
<section class="wrap section" aria-labelledby="audiences">
  <h2 id="audiences" class="center">${esc(h.audiences.title)}</h2>
  <div class="grid2">
    <div class="panel">
      ${icon('phone', 'icon accent')}
      <h3>${esc(h.audiences.takers.title)}</h3>
      <p>${esc(h.audiences.takers.text)}</p>
      ${list(h.audiences.takers.bullets)}
      <a class="button ghost" href="${pagePath(ctx.language, 'takers', ctx.base)}">${esc(h.audiences.takers.more)} ${icon('arrow', 'icon small')}</a>
    </div>
    <div class="panel">
      ${icon('target', 'icon accent')}
      <h3>${esc(h.audiences.merchants.title)}</h3>
      <p>${esc(h.audiences.merchants.text)}</p>
      ${list(h.audiences.merchants.bullets)}
      <a class="button ghost" href="${pagePath(ctx.language, 'merchants', ctx.base)}">${esc(h.audiences.merchants.more)} ${icon('arrow', 'icon small')}</a>
    </div>
  </div>
</section>
<section class="wrap section" aria-labelledby="how">
  <h2 id="how" class="center">${esc(h.how.title)}</h2>
  ${steps(h.how.steps)}
</section>
<section class="wrap section split" aria-labelledby="surveys">
  <div>
    <p class="eyebrow">${esc(h.surveys.eyebrow)}</p>
    <h2 id="surveys">${esc(h.surveys.title)}</h2>
    <p class="lead">${esc(h.surveys.text)}</p>
    ${list(h.surveys.bullets)}
    <a class="button ghost" href="${pagePath(ctx.language, 'surveys', ctx.base)}">${esc(h.surveys.more)} ${icon('arrow', 'icon small')}</a>
  </div>
  <div class="survey-mock" aria-hidden="true">
    ${h.surveys.mock.map((row) => `<div class="row"><span>${esc(row.label)}</span><span class="track"><i style="--w:${Number(row.pct)}%"></i></span><span class="pct">${Number(row.pct)}%</span></div>`).join('')}
  </div>
</section>
${tryIt(ctx)}
<section class="wrap section" aria-labelledby="pricing-teaser">
  <div class="panel wide">
    <h2 id="pricing-teaser">${esc(h.pricing.title)}</h2>
    <p class="lead">${esc(h.pricing.text)}</p>
    <a class="button primary" href="${pagePath(ctx.language, 'pricing', ctx.base)}">${esc(h.pricing.cta)} ${icon('arrow', 'icon small')}</a>
  </div>
</section>
<section class="wrap section" aria-labelledby="faq">
  <h2 id="faq" class="center">${esc(h.faq.title)}</h2>
  <div class="faq">${h.faq.items.map((f) => `<details><summary>${esc(f.q)}</summary><p>${esc(f.a)}</p></details>`).join('')}</div>
</section>`
}

function featurePage(ctx, key) {
  const p = ctx.content[key]
  return `<section class="hero compact">
  <div class="wrap">
    <p class="eyebrow">${esc(p.eyebrow)}</p>
    <h1>${esc(p.title)}</h1>
    <p class="lead">${esc(p.lead)}</p>
    ${cta(ctx.content)}
  </div>
</section>
${p.sections.map((s, i) => `<section class="wrap section${i % 2 ? ' alt' : ''}" aria-labelledby="s${i}">
  <h2 id="s${i}">${esc(s.title)}</h2>
  <p class="lead">${esc(s.text)}</p>
  ${s.cards ? cards(s.cards) : ''}${s.bullets ? list(s.bullets) : ''}${s.steps ? steps(s.steps) : ''}
</section>`).join('')}
<section class="wrap section" aria-labelledby="closing">
  <div class="panel wide"><h2 id="closing">${esc(p.closing.title)}</h2><p class="lead">${esc(p.closing.text)}</p>${cta(ctx.content)}</div>
</section>`
}

function pricing(ctx) {
  const p = ctx.content.pricing
  return `<section class="hero compact">
  <div class="wrap">
    <p class="eyebrow">${esc(p.eyebrow)}</p>
    <h1>${esc(p.title)}</h1>
    <p class="lead">${esc(p.lead)}</p>
  </div>
</section>
<section class="wrap section" aria-labelledby="plans">
  <h2 id="plans" class="visually-hidden">${esc(p.plansTitle)}</h2>
  <div class="plans">${p.plans.map((plan) => `<div class="plan${plan.highlight ? ' highlight' : ''}">
    ${plan.badge ? `<span class="chip">${esc(plan.badge)}</span>` : ''}
    <h3>${esc(plan.name)}</h3>
    <p class="price"><span class="amount">${esc(plan.price)}</span>${plan.unit ? `<span class="unit">${esc(plan.unit)}</span>` : ''}</p>
    <p class="muted">${esc(plan.note)}</p>
    ${list(plan.features)}
    <a class="button ${plan.highlight ? 'primary' : 'ghost'}" href="${esc(plan.href)}"${plan.signup ? ` data-signup="${plan.signup}"` : ''}${plan.signup === 'sponsor' ? ' target="_blank" rel="noopener"' : ''}>${esc(plan.cta)}</a>
  </div>`).join('')}</div>
</section>
<section class="wrap section" aria-labelledby="usage">
  <h2 id="usage">${esc(p.usage.title)}</h2>
  <p class="lead">${esc(p.usage.text)}</p>
  <div class="table-wrap"><table class="fees">
    <thead><tr><th scope="col">${esc(p.usage.columns.item)}</th><th scope="col">${esc(p.usage.columns.price)}</th><th scope="col">${esc(p.usage.columns.note)}</th></tr></thead>
    <tbody>${p.usage.rows.map((r) => `<tr><th scope="row">${esc(r.item)}</th><td class="num">${esc(r.price)}</td><td>${esc(r.note)}</td></tr>`).join('')}</tbody>
  </table></div>
  <p class="muted small">${esc(p.disclaimer)}</p>
</section>
<section class="wrap section" aria-labelledby="pricing-faq">
  <h2 id="pricing-faq" class="center">${esc(p.faq.title)}</h2>
  <div class="faq">${p.faq.items.map((f) => `<details><summary>${esc(f.q)}</summary><p>${esc(f.a)}</p></details>`).join('')}</div>
</section>`
}

function contact(ctx) {
  const c = ctx.content.contact
  return `<section class="hero compact">
  <div class="wrap">
    <p class="eyebrow">${esc(c.eyebrow)}</p>
    <h1>${esc(c.title)}</h1>
    <p class="lead">${esc(c.lead)}</p>
  </div>
</section>
<section class="wrap section" aria-labelledby="ways">
  <h2 id="ways" class="visually-hidden">${esc(c.waysTitle)}</h2>
  <div class="grid3">
    <div class="card">${icon('mail', 'icon accent')}<h3>${esc(c.email.title)}</h3><p>${esc(c.email.text)}</p><a class="button primary" href="mailto:${esc(c.email.address)}">${esc(c.email.address)}</a></div>
    <div class="card">${icon('users', 'icon accent')}<h3>${esc(c.merchants.title)}</h3><p>${esc(c.merchants.text)}</p><a class="button ghost" href="/sponsor/signup" data-signup="sponsor" target="_blank" rel="noopener">${esc(c.merchants.cta)}</a></div>
    <div class="card">${icon('phone', 'icon accent')}<h3>${esc(c.takers.title)}</h3><p>${esc(c.takers.text)}</p><a class="button ghost" href="/taker/help">${esc(c.takers.cta)}</a></div>
  </div>
  <p class="muted small">${esc(c.note)}</p>
</section>`
}

const BODIES = { home, merchants: (ctx) => featurePage(ctx, 'merchants'), takers: (ctx) => featurePage(ctx, 'takers'),
  surveys: (ctx) => featurePage(ctx, 'surveys'), pricing, contact }

export function renderPage(ctx) {
  const { language, page, content, base, assets } = ctx
  const meta = content.meta[page]
  const alternates = ['en', 'zh', 'ja'].map((lang) =>
    `<link rel="alternate" hreflang="${LOCALES[lang]}" href="${pagePath(lang, page, base)}" />`).join('\n    ')
  return `<!doctype html>
<html lang="${LOCALES[language]}" data-base="${base}" data-page="${page}">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <meta name="description" content="${esc(meta.description)}" />
    <meta name="color-scheme" content="light dark" />
    <meta property="og:title" content="${esc(meta.title)}" />
    <meta property="og:description" content="${esc(meta.description)}" />
    <meta property="og:type" content="website" />
    <link rel="icon" type="image/png" href="${base}icon-192.png" />
    <link rel="apple-touch-icon" href="${base}icon-192.png" />
    ${alternates}
    <link rel="alternate" hreflang="x-default" href="${pagePath('en', page, base)}" />
    <link rel="stylesheet" href="${assets.css}" />
    <script type="module" src="${assets.js}"></script>
    <title>${esc(meta.title)}</title>
  </head>
  <body>
    <a class="skip" href="#main">${esc(content.skip)}</a>
    ${header(ctx)}
    <main id="main">
${BODIES[page](ctx)}
    </main>
    ${footer(ctx)}
  </body>
</html>
`
}
