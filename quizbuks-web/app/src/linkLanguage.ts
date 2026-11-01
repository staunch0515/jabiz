import { changeLanguage, languages, type Language } from '@jabiz/client'

/*
 * The language a link or a QR code carries (Q59b, docs/quizbuks/plans/Q59-languages.md): `?lang=ja|zh|en`, the quiz's,
 * added by the sponsor console's share links and posters and by the invitation mail. Someone new to the app sees it in
 * that language from the first page (and keeps it); someone who already chose a language keeps theirs: a link never
 * changes it behind their back, and the publication opens either way (links are not limited by the language switches).
 * Q73b: a publication offering the link's language opens in it (`linkLanguageOf`), whatever the interface's.
 */

/** Where the platform remembers the interface language (`@jabiz/client` i18n; set only by changing it). */
const REMEMBERED = 'jabiz.language'
const PARAM = 'lang'

/** Q73b: the language the link opened its page in, for that page only (a publication offering it reads in it). */
let opened: { path: string; language: string } | null = null

/** The language a link carried for the page at `path` (the one it opened), else null. */
export function linkLanguageOf(path: string): string | null {
  return opened && opened.path === path ? opened.language : null
}

function chosenBefore(win: Window): boolean {
  try {
    return win.localStorage.getItem(REMEMBERED) !== null
  } catch {
    return false
  }
}

/**
 * Applies a link's language before the app renders (from main.tsx) and takes the parameter out of the address, so that
 * it is not carried on to other pages or into a link the taker shares.
 */
export function applyLinkLanguage(win: Window = window) {
  const url = new URL(win.location.href)
  const language = url.searchParams.get(PARAM)
  if (language === null) return
  url.searchParams.delete(PARAM)
  win.history.replaceState(win.history.state, '', `${url.pathname}${url.search}${url.hash}`)
  if (!(languages as readonly string[]).includes(language)) return
  opened = { path: url.pathname, language }
  if (!chosenBefore(win)) {
    void changeLanguage(language as Language)
  }
}
