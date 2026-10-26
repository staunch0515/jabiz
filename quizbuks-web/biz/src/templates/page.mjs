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

/*
 * The brand's drawings (Q32, the same shapes as the apps' qb/Illustrations.tsx), in the site's tokens: the coin of the
 * wordmark and the illustrations of the hero, the steps and the feature pages. All decorative (aria-hidden).
 */
const COIN = `<circle cx="24" cy="24" r="22" fill="var(--coin)"/><circle cx="24" cy="24" r="17" fill="none" stroke="var(--coin-deep)" stroke-width="2.5"/><path d="M24 34c-7 0-9-7-9-11 7 0 11 3 11 9 0 .7 0 1.4-.2 2H24z" fill="var(--leaf-deep)"/><path d="M24 34c7 0 9-7 9-11-7 0-11 3-11 9 0 .7 0 1.4.2 2H24z" fill="var(--leaf)"/><path d="M24 33V22" stroke="var(--ink)" stroke-width="1.6" stroke-linecap="round"/>`

const ART = {
  quiz: `<ellipse cx="120" cy="150" rx="92" ry="14" fill="var(--sky)"/><rect x="52" y="30" width="136" height="110" rx="14" fill="var(--card)" stroke="var(--line)" stroke-width="2"/><rect x="68" y="48" width="78" height="10" rx="5" fill="var(--ink)" opacity=".85"/><rect x="68" y="66" width="104" height="8" rx="4" fill="var(--muted)" opacity=".5"/><rect x="68" y="88" width="104" height="16" rx="8" fill="var(--sky)" stroke="var(--leaf-deep)" stroke-width="2"/><path d="M76 96l5 5 9-10" fill="none" stroke="var(--leaf-deep)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/><rect x="68" y="112" width="104" height="16" rx="8" fill="none" stroke="var(--line)" stroke-width="2"/><circle cx="190" cy="42" r="22" fill="var(--coin)"/><circle cx="190" cy="42" r="15" fill="none" stroke="var(--coin-deep)" stroke-width="2.5"/><path d="M186 36l4 12 4-12" fill="none" stroke="var(--ink)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M26 118c10-18 30-18 40 0" fill="none" stroke="var(--leaf)" stroke-width="4" stroke-linecap="round"/><path d="M46 118V96" stroke="var(--leaf-deep)" stroke-width="4" stroke-linecap="round"/>`,
  reward: `<ellipse cx="120" cy="152" rx="92" ry="14" fill="var(--sky)"/>${[0, 1, 2, 3].map((i) => `<g transform="translate(0 ${-i * 16})"><ellipse cx="110" cy="136" rx="46" ry="14" fill="var(--coin-deep)"/><ellipse cx="110" cy="130" rx="46" ry="14" fill="var(--coin)"/></g>`).join('')}<ellipse cx="110" cy="82" rx="30" ry="8" fill="none" stroke="var(--coin-deep)" stroke-width="2"/><path d="M150 90c22-26 48-30 64-28-4 22-22 42-50 44" fill="var(--leaf)" stroke="var(--leaf-deep)" stroke-width="2"/><path d="M152 104c14-14 30-26 54-36" fill="none" stroke="var(--leaf-deep)" stroke-width="2" stroke-linecap="round"/><circle cx="40" cy="48" r="4" fill="var(--coin)"/><circle cx="200" cy="140" r="5" fill="var(--leaf)"/><circle cx="60" cy="110" r="3" fill="var(--leaf-deep)"/>`,
  prize: `<ellipse cx="120" cy="152" rx="92" ry="14" fill="var(--sky)"/><rect x="40" y="100" width="160" height="44" rx="8" fill="var(--card)" stroke="var(--line)" stroke-width="2"/><path d="M36 100h168l-10-22H46z" fill="var(--leaf-deep)"/><path d="M46 78h148l-6 14H52z" fill="var(--leaf)"/><rect x="92" y="56" width="56" height="44" rx="6" fill="var(--coin)"/><rect x="92" y="56" width="56" height="12" rx="4" fill="var(--coin-deep)"/><rect x="116" y="56" width="8" height="44" fill="var(--ink)" opacity=".7"/><path d="M120 56c-10-14-24-10-18 0 4 6 12 4 18 0zm0 0c10-14 24-10 18 0-4 6-12 4-18 0z" fill="var(--leaf)" stroke="var(--leaf-deep)" stroke-width="1.5"/><rect x="150" y="110" width="36" height="24" rx="4" fill="var(--bg)" stroke="var(--line)" stroke-width="2"/>${[154, 160, 165, 171, 176, 181].map((x, i) => `<rect x="${x}" y="114" width="${i % 2 ? 2 : 3}" height="16" fill="var(--ink)"/>`).join('')}`,
  share: `<ellipse cx="120" cy="152" rx="92" ry="14" fill="var(--sky)"/><rect x="84" y="24" width="72" height="124" rx="12" fill="var(--card)" stroke="var(--line)" stroke-width="2"/><rect x="102" y="32" width="36" height="4" rx="2" fill="var(--line)"/><rect x="98" y="52" width="44" height="44" rx="4" fill="var(--bg)" stroke="var(--line)" stroke-width="2"/><rect x="104" y="58" width="12" height="12" rx="2" fill="var(--ink)"/><rect x="124" y="58" width="12" height="12" rx="2" fill="var(--ink)"/><rect x="104" y="78" width="12" height="12" rx="2" fill="var(--ink)"/><path d="M124 78h5v5h-5zM131 78h5v5h-5zM124 85h5v5h-5zM131 85h5v5h-5z" fill="var(--leaf-deep)"/><rect x="98" y="106" width="44" height="12" rx="6" fill="var(--leaf)"/><path d="M40 60c20-10 40-6 52 18M200 60c-20-10-40-6-52 18" fill="none" stroke="var(--leaf-deep)" stroke-width="3" stroke-linecap="round" stroke-dasharray="6 8"/><circle cx="40" cy="60" r="7" fill="var(--coin)"/><circle cx="200" cy="60" r="7" fill="var(--coin)"/>`,
  plan: `<ellipse cx="120" cy="152" rx="92" ry="14" fill="var(--sky)"/><rect x="70" y="26" width="100" height="120" rx="10" fill="var(--leaf-deep)"/><rect x="82" y="42" width="76" height="92" rx="5" fill="var(--card)"/><rect x="100" y="16" width="40" height="18" rx="6" fill="var(--coin)"/><path d="M94 66h52M94 84h52M94 102h32" stroke="var(--ink)" stroke-width="5" stroke-linecap="round" opacity=".35"/><circle cx="152" cy="118" r="18" fill="var(--leaf)"/><path d="M143 118l6 6 12-13" stroke="var(--paper)" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" fill="none"/>`,
  growth: `<ellipse cx="120" cy="152" rx="92" ry="14" fill="var(--sky)"/><rect x="52" y="100" width="30" height="46" rx="5" fill="var(--leaf-deep)"/><rect x="94" y="74" width="30" height="72" rx="5" fill="var(--leaf)"/><rect x="136" y="48" width="30" height="98" rx="5" fill="var(--coin)"/><circle cx="151" cy="30" r="14" fill="var(--coin-deep)"/><path d="M151 30V10" stroke="var(--leaf-deep)" stroke-width="3" stroke-linecap="round"/><path d="M151 22c-12-1-19-9-20-21 12 0 19 7 20 21z" fill="var(--leaf)"/><path d="M151 16c12-1 19-9 20-21-12 0-19 7-20 21z" fill="var(--leaf-deep)"/><path d="M46 146h130" stroke="var(--ink)" stroke-width="2" stroke-linecap="round" opacity=".3"/>`,
}

/** An illustration, decorative. */
export function art(name, className = 'art') {
  return `<svg class="${className}" viewBox="0 0 240 180" aria-hidden="true">${ART[name] ?? ''}</svg>`
}

/** The wordmark: the coin with a leaf, then the name. */
export function wordmark(href) {
  return `<a class="brand" href="${href}"><svg class="coin" viewBox="0 0 48 48" aria-hidden="true">${COIN}</svg><span>QuizBuks</span></a>`
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
    ${wordmark(pagePath(language, 'home', base))}
    <button class="menu-toggle" type="button" data-menu-toggle aria-expanded="false" aria-controls="site-menu" aria-label="${esc(content.menu)}"><span></span><span></span><span></span></button>
    <nav id="site-menu" class="menu" data-menu aria-label="${esc(content.navLabel)}">
      <div class="links">${nav}</div>
      <div class="lang" role="group" aria-label="${esc(content.languageLabel)}">${languages}</div>
      <div class="signin" role="group" aria-label="${esc(content.signIn.label)}">
        <a href="/taker/login" data-signup="taker" data-testid="signin-taker">${esc(content.signIn.taker)}</a>
        <a href="/sponsor/login" data-signup="sponsor" target="_blank" rel="noopener" data-testid="signin-sponsor">${esc(content.signIn.sponsor)}</a>
      </div>
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
      ${wordmark(pagePath(language, 'home', base))}
      <p class="muted">${esc(f.tagline)}</p>
    </div>
    <div><h3>${esc(f.product)}</h3><ul class="plain">
      <li><a href="${pagePath(language, 'merchants', base)}">${esc(content.nav.merchants)}</a></li>
      <li><a href="${pagePath(language, 'takers', base)}">${esc(content.nav.takers)}</a></li>
      <li><a href="${pagePath(language, 'surveys', base)}">${esc(content.nav.surveys)}</a></li>
      <li><a href="${pagePath(language, 'pricing', base)}">${esc(content.nav.pricing)}</a></li></ul></div>
    <div><h3>${esc(f.open)}</h3><ul class="plain">
      <li><a href="/taker/login" data-signup="taker">${esc(content.signIn.taker)}</a></li>
      <li><a href="/sponsor/login" data-signup="sponsor" target="_blank" rel="noopener">${esc(content.signIn.sponsor)}</a></li>
      <li><a href="${pagePath(language, 'contact', base)}">${esc(content.nav.contact)}</a></li></ul></div>
    <div><h3>${esc(f.legal)}</h3><ul class="plain">
      <li><a href="/taker/legal/terms">${esc(f.terms)}</a></li>
      <li><a href="/taker/legal/sponsor-terms">${esc(f.sponsorTerms)}</a></li>
      <li><a href="/taker/legal/privacy">${esc(f.privacy)}</a></li></ul></div>
  </div>
  <div class="wrap fine"><span>© 2026 QuizBuks</span><span>${esc(f.rights)}</span></div>
</footer>`
}

function cards(items, cols = 'grid3') {
  return `<div class="${cols}">${items.map((c) => `<div class="card">${c.icon ? icon(c.icon, 'icon accent') : ''}<h3>${esc(c.title)}</h3><p>${esc(c.text)}</p></div>`).join('')}</div>`
}

const STEP_ART = ['plan', 'share', 'quiz', 'prize']

/** Steps in order; the home page's four get a drawing each (`illustrated`). */
function steps(items, illustrated = false) {
  return `<ol class="steps">${items.map((s, i) => `<li>${illustrated && STEP_ART[i] ? art(STEP_ART[i]) : ''}<span class="num" aria-hidden="true">${i + 1}</span><div><h3>${esc(s.title)}</h3><p>${esc(s.text)}</p></div></li>`).join('')}</ol>`
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
      ${art('reward')}
      <div class="phone">
        <div class="phone-bar"></div>
        <div class="phone-card"><span class="chip">${esc(h.mock.chip)}</span><strong>${esc(h.mock.question)}</strong>
          <div class="opt">${esc(h.mock.a)}</div><div class="opt selected">${esc(h.mock.b)}</div><div class="opt">${esc(h.mock.c)}</div>
          <div class="reward">${icon('gift', 'icon small')} ${esc(h.mock.reward)}</div></div>
      </div>
      <div class="stat-card"><span class="stat-label">${esc(h.mock.statLabel)}</span><span class="stat-value">${esc(h.mock.statValue)}</span><span class="meter"><i></i></span></div>
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
  ${steps(h.how.steps, true)}
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

const PAGE_ART = { merchants: 'growth', takers: 'quiz', surveys: 'plan' }

function featurePage(ctx, key) {
  const p = ctx.content[key]
  return `<section class="hero compact">
  <div class="wrap hero-split">
    <div>
      <p class="eyebrow">${esc(p.eyebrow)}</p>
      <h1>${esc(p.title)}</h1>
      <p class="lead">${esc(p.lead)}</p>
      ${cta(ctx.content)}
    </div>
    ${art(PAGE_ART[key], 'hero-art')}
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
  <p class="muted small center">${esc(p.currencyNote)}</p>
  <div class="plans">${p.plans.map((plan) => `<div class="plan${plan.highlight ? ' highlight' : ''}">
    ${plan.badge ? `<span class="chip">${esc(plan.badge)}</span>` : ''}
    <h3>${esc(plan.name)}</h3>
    <p class="price"><span class="amount">${esc(plan.price)}</span>${plan.unit ? `<span class="unit">${esc(plan.unit)}</span>` : ''}</p>
    <p class="muted">${esc(plan.note)}</p>
    ${list(plan.features)}
    <a class="button ${plan.highlight ? 'primary' : 'ghost'}" href="${plan.page ? pagePath(ctx.language, plan.page, ctx.base) : esc(plan.href)}"${plan.signup ? ` data-signup="${plan.signup}"` : ''}${plan.signup === 'sponsor' ? ' target="_blank" rel="noopener"' : ''}>${esc(plan.cta)}</a>
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
  const siteUrl = (ctx.siteUrl ?? '').replace(/\/$/, '')
  const meta = content.meta[page]
  const alternates = ['en', 'zh', 'ja'].map((lang) =>
    `<link rel="alternate" hreflang="${LOCALES[lang]}" href="${siteUrl}${pagePath(lang, page, base)}" />`).join('\n    ')
  return `<!doctype html>
<html lang="${LOCALES[language]}" data-base="${base}" data-page="${page}" data-language="${language}">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <meta name="description" content="${esc(meta.description)}" />
    <meta name="color-scheme" content="light dark" />
    <meta property="og:title" content="${esc(meta.title)}" />
    <meta property="og:description" content="${esc(meta.description)}" />
    <meta property="og:type" content="website" />
    <meta property="og:url" content="${siteUrl}${pagePath(language, page, base)}" />
    <meta property="og:locale" content="${{ en: 'en_US', zh: 'zh_CN', ja: 'ja_JP' }[language]}" />
    <meta property="og:image" content="${siteUrl}${base}icon-512.png" />
    <link rel="canonical" href="${siteUrl}${pagePath(language, page, base)}" />
    <link rel="icon" type="image/png" href="${base}icon-192.png" />
    <link rel="apple-touch-icon" href="${base}icon-192.png" />
    ${alternates}
    <link rel="alternate" hreflang="x-default" href="${siteUrl}${pagePath('en', page, base)}" />
    <link rel="stylesheet" href="${assets.css}" />
    <script src="${assets.js}"></script>
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
