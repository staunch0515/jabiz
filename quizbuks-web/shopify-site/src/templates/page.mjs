// The one layout of the Shopify app's public pages and the body of each page, as template functions over the
// language's content. Everything the content gives is escaped; a text may name the support address as {email}, which
// the template turns into a mail link. No scripts, no inline styles: the platform's content security policy allows
// neither, and the pages need none.

import { LANGUAGES } from '../content/index.mjs'

const PAGE_PATH = { home: '', privacy: 'privacy/', support: 'support/' }
const LANGUAGE_NAMES = { en: 'English', zh: '中文', ja: '日本語' }
const LOCALES = { en: 'en', zh: 'zh-Hans', ja: 'ja' }

export function esc(text) {
  return String(text ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;',
    "'": '&#39;' })[c])
}

export function pagePath(language, page, base) {
  return `${base}${language === 'en' ? '' : `${language}/`}${PAGE_PATH[page]}`
}

/** A text with {email} as a mail link to the support address; everything else escaped. */
export function withEmail(text, email) {
  return String(text).split('{email}').map(esc).join(`<a href="mailto:${esc(email)}">${esc(email)}</a>`)
}

function paragraphs(texts, email) {
  return texts.map((text) => `<p>${withEmail(text, email)}</p>`).join('\n')
}

function sections(list, email) {
  return list.map((section) => `<section>
  <h2>${esc(section.title)}</h2>
  ${paragraphs(section.paragraphs ?? [], email)}
  ${section.items ? `<ul>${section.items.map((item) => `<li>${withEmail(item, email)}</li>`).join('')}</ul>` : ''}
</section>`).join('\n')
}

function body({ page, content, supportEmail, language, base }) {
  if (page === 'home') {
    const home = content.home
    return `<section class="hero">
  <h1>${esc(home.title)}</h1>
  <p class="lead">${esc(home.lead)}</p>
</section>
<section>
  <h2>${esc(home.howTitle)}</h2>
  <ol class="steps">${home.steps.map((step) => `<li><strong>${esc(step.title)}</strong><span>${esc(step.text)}</span></li>`).join('')}</ol>
</section>
${sections(home.sections, supportEmail)}
<p class="more"><a href="${pagePath(language, 'support', base)}">${esc(content.nav.support)}</a> · <a href="${pagePath(language, 'privacy', base)}">${esc(content.nav.privacy)}</a></p>`
  }
  const text = content[page]
  return `<h1>${esc(text.title)}</h1>
${text.updated ? `<p class="updated">${esc(text.updated)}</p>` : ''}
${paragraphs(text.intro ?? [], supportEmail)}
${sections(text.sections, supportEmail)}`
}

export function renderPage({ language, page, content, base, siteUrl, supportEmail, css }) {
  const nav = Object.entries(content.nav).map(([key, label]) =>
    `<a href="${pagePath(language, key, base)}"${key === page ? ' aria-current="page"' : ''}>${esc(label)}</a>`).join('')
  const languages = LANGUAGES.map((lang) => lang === language
    ? `<span aria-current="true">${LANGUAGE_NAMES[lang]}</span>`
    : `<a href="${pagePath(lang, page, base)}" lang="${LOCALES[lang]}" hreflang="${LOCALES[lang]}">${LANGUAGE_NAMES[lang]}</a>`).join('')
  const alternates = LANGUAGES.map((lang) =>
    `<link rel="alternate" hreflang="${LOCALES[lang]}" href="${siteUrl}${pagePath(lang, page, base)}">`).join('\n')
  const title = page === 'home' ? content.siteName : `${content[page].title} · ${content.siteName}`
  return `<!doctype html>
<html lang="${LOCALES[language]}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<meta name="description" content="${esc(content.description)}">
<link rel="canonical" href="${siteUrl}${pagePath(language, page, base)}">
${alternates}
<link rel="icon" href="${base}icon-192.png">
<link rel="stylesheet" href="${css}">
</head>
<body>
<a class="skip" href="#main">${esc(content.skip)}</a>
<header class="top">
  <div class="wrap bar">
    <a class="brand" href="${pagePath(language, 'home', base)}">${esc(content.siteName)}</a>
    <nav aria-label="${esc(content.navLabel)}">${nav}</nav>
    <div class="lang" role="group" aria-label="${esc(content.languageLabel)}">${languages}</div>
  </div>
</header>
<main id="main" class="wrap">
${body({ page, content, supportEmail, language, base })}
</main>
<footer class="wrap foot">
  <p>${withEmail(content.footer, supportEmail)}</p>
</footer>
</body>
</html>
`
}
