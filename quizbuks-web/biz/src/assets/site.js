// The business site's one script (docs/quizbuks/plans/Q31-biz-site.md): the language a first visitor is sent to, the
// language switch that is remembered, and the two sign-up buttons. No framework, no inline code (the platform's
// content security policy allows scripts from this origin only). The pure functions are exported for the tests; the
// build strips the `export` keywords and serves the file as a classic script in <head>, so that the language redirect
// runs before the English page is painted (the DOM wiring waits for DOMContentLoaded).

export const LANGUAGES = ['en', 'zh', 'ja']
const STORAGE_KEY = 'qb-biz-lang'

/** The site's language for the browser's languages: zh and ja by their primary tag, English for everything else. */
export function pickLanguage(languages) {
  for (const tag of languages ?? []) {
    const primary = String(tag).toLowerCase().split('-')[0]
    if (primary === 'zh' || primary === 'ja') return primary
    if (primary === 'en') return 'en'
  }
  return 'en'
}

/** The language and the page of a path under the base: `/biz/zh/pricing/` → { language: 'zh', page: 'pricing/' }. */
export function parsePath(pathname, base) {
  if (!pathname.startsWith(base)) return null
  const rest = pathname.slice(base.length)
  const [first, ...more] = rest.split('/')
  if (LANGUAGES.includes(first) && first !== 'en') return { language: first, page: more.join('/') }
  return { language: 'en', page: rest }
}

/** The address of the same page in another language. */
export function pathFor(language, page, base) {
  return `${base}${language === 'en' ? '' : `${language}/`}${page}`
}

/**
 * Where a first visit of the English root should go: the remembered choice, else the browser's language; null when
 * the page already is in that language or the visitor chose one before.
 */
export function redirectTarget({ pathname, base, stored, browserLanguages }) {
  const here = parsePath(pathname, base)
  if (!here) return null
  const wanted = stored && LANGUAGES.includes(stored) ? stored : pickLanguage(browserLanguages)
  if (wanted === here.language) return null
  // A visitor who opened a language page directly (a shared link) stays on it; only the English pages redirect.
  if (here.language !== 'en') return null
  return pathFor(wanted, here.page, base)
}

/** Whether the visitor is on a phone: a coarse pointer or a narrow screen. */
export function isPhone(win) {
  try {
    return win.matchMedia('(pointer: coarse)').matches || win.innerWidth < 768
  } catch {
    return false
  }
}

/** The phone-sized window's size: a phone's 390 × 844 widened by a fifth (Q34), so the app's bars fit on one line. */
export const PHONE_WIDTH = 468
export const PHONE_HEIGHT = 844

/**
 * The window features of the phone-sized window a taker opens on a desktop: centred on the screen and never taller
 * than it. No `noopener`: with it `window.open` returns null even when the window opened, which would make the
 * blocked-popup fallback fire too. Browsers always show a popup's address bar (read-only); no feature hides it.
 */
export function phoneWindow(win) {
  const screen = win.screen ?? {}
  const availWidth = Number(screen.availWidth) || PHONE_WIDTH
  const availHeight = Number(screen.availHeight) || PHONE_HEIGHT
  const width = Math.min(PHONE_WIDTH, availWidth)
  const height = Math.min(PHONE_HEIGHT, availHeight - 40)
  const left = Math.max(0, Math.round((Number(screen.availLeft) || 0) + (availWidth - width) / 2))
  const top = Math.max(0, Math.round((Number(screen.availTop) || 0) + (availHeight - height) / 2))
  return `popup=yes,width=${width},height=${height},left=${left},top=${top},resizable=yes,scrollbars=yes`
}

/**
 * Opens the app: a taker on a desktop gets a separate phone-sized window (a new tab when the browser blocks it), a
 * taker on a phone goes straight there, a merchant always gets a new tab. Used by the sign-up and sign-in links.
 */
export function openSignUp(kind, href, win) {
  if (kind === 'taker' && !isPhone(win)) {
    const opened = win.open(href, 'quizbuks-taker', phoneWindow(win))
    if (!opened) win.open(href, '_blank', 'noopener')
    return
  }
  if (kind === 'taker') {
    win.location.assign(href)
    return
  }
  win.open(href, '_blank', 'noopener')
}

function storage(win) {
  try {
    return win.localStorage
  } catch {
    return null
  }
}

/** Whether the visitor came from a page of this site: following its own links is a choice, not a first visit. */
export function cameFromThisSite(referrer, origin) {
  try {
    return Boolean(referrer) && new URL(referrer).origin === origin
  } catch {
    return false
  }
}

/** The click of a sign-up button that the script should leave to the browser (a new tab or window asked by a modifier). */
export function isModifiedClick(event) {
  return Boolean(event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || (event.button ?? 0) !== 0)
}

function install(win) {
  const doc = win.document
  const base = doc.documentElement.dataset.base ?? '/biz/'
  // The page's own language (the server may answer an unknown directory with the English home).
  const language = doc.documentElement.dataset.language ?? 'en'
  const store = storage(win)
  let stored = null
  try { stored = store?.getItem(STORAGE_KEY) ?? null } catch { stored = null }
  const target = cameFromThisSite(doc.referrer, win.location.origin) ? null
    : redirectTarget({ pathname: win.location.pathname, base, stored,
      browserLanguages: win.navigator.languages ?? [win.navigator.language] })
  if (target) {
    win.location.replace(target + win.location.search + win.location.hash)
    return
  }
  // The visible switch remembers the choice; a direct visit of a language page counts as a choice too.
  if (store) {
    try { store.setItem(STORAGE_KEY, language) } catch { /* private mode: nothing to remember */ }
  }
  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', () => wire(win, doc, store))
  else wire(win, doc, store)
}

function wire(win, doc, store) {
  for (const link of doc.querySelectorAll('[data-lang]')) {
    link.addEventListener('click', () => {
      try { store?.setItem(STORAGE_KEY, link.dataset.lang) } catch { /* ignore */ }
    })
  }
  for (const button of doc.querySelectorAll('[data-signup]')) {
    button.addEventListener('click', (event) => {
      if (isModifiedClick(event)) return
      event.preventDefault()
      openSignUp(button.dataset.signup, button.getAttribute('href'), win)
    })
  }
  const menu = doc.querySelector('[data-menu]')
  const toggle = doc.querySelector('[data-menu-toggle]')
  if (menu && toggle) {
    toggle.addEventListener('click', () => {
      const open = menu.classList.toggle('open')
      toggle.setAttribute('aria-expanded', String(open))
    })
  }
}

if (typeof window !== 'undefined' && typeof document !== 'undefined' && document.documentElement.dataset.base) {
  install(window)
}
