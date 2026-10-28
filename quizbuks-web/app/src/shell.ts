/*
 * Whether the app runs inside one of QuizBuks' store apps (docs/quizbuks/plans/Q49b-android.md): on Android a Trusted
 * Web Activity, which is Chrome itself, so the page cannot tell it from a home-screen PWA by its display mode. The
 * Android shell adds `shell=android` to every address it opens (its LauncherActivity), and Chrome gives the first page
 * the referrer `android-app://<package>`. Neither survives a navigation within the app, so what the first page saw
 * is kept for the tab (the shell's tab is its own; a tab of the browser never sees it), and the parameter is taken
 * off the address so that a copied or shared link does not carry it.
 */

/** The store app the page runs in. */
export type NativeShell = 'android'

/** The Android app's package (quizbuks-mobile/android/twa-manifest.json `packageId`). */
export const ANDROID_PACKAGE = 'com.quizbuks.app'
const PARAM = 'shell'
const KEY = 'qb.shell'

function seen(win: Window): NativeShell | null {
  try {
    const url = new URL(win.location.href)
    if (url.searchParams.get(PARAM) === 'android') return 'android'
  } catch {
    // No address to read: only the referrer is left.
  }
  const referrer = win.document.referrer
  return referrer === `android-app://${ANDROID_PACKAGE}` || referrer.startsWith(`android-app://${ANDROID_PACKAGE}/`)
    ? 'android'
    : null
}

function dropParam(win: Window) {
  try {
    const url = new URL(win.location.href)
    if (!url.searchParams.has(PARAM)) return
    url.searchParams.delete(PARAM)
    win.history.replaceState(win.history.state, '', url.pathname + url.search + url.hash)
  } catch {
    // The address keeps the parameter; harmless.
  }
}

/**
 * Notes, before the app renders, that this tab is the store app's (from main.tsx): later pages of the tab no longer see
 * the referrer or the parameter.
 */
export function rememberShell(win: Window = window) {
  const shell = seen(win)
  if (shell) {
    try {
      win.sessionStorage.setItem(KEY, shell)
    } catch {
      // Blocked storage: this page still knows; a later one may offer to install, which the shell then ignores.
    }
  }
  dropParam(win)
}

/** The store app this tab runs in, or null in a browser or a home-screen PWA. */
export function nativeShell(win: Window = window): NativeShell | null {
  try {
    if (win.sessionStorage.getItem(KEY) === 'android') return 'android'
  } catch {
    // Blocked storage: what the page itself sees.
  }
  return seen(win)
}
