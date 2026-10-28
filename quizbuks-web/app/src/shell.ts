/*
 * Whether the app runs inside one of QuizBuks' store apps: on Android a Trusted Web Activity
 * (docs/quizbuks/plans/Q49b-android.md), which is Chrome itself, so the page cannot tell it from a home-screen PWA by
 * its display mode; on iOS a Capacitor web view (Q49c-ios.md), which is not standalone, so the platform would offer
 * Safari's "Add to Home Screen". Each shell adds `shell=android` or `shell=ios` to every address it opens (Android's
 * LauncherActivity, iOS's QuizBuksScope); Chrome also gives a TWA's first page the referrer `android-app://<package>`,
 * and the iOS web view adds `QuizBuksApp/ios` to its user agent. The parameter and the referrer do not survive a
 * navigation within the app, so what the first page saw is kept for the tab (the shell's tab is its own; a tab of the
 * browser never sees it), and the parameter is taken off the address so that a copied or shared link does not carry it.
 */

/** The store app the page runs in. */
export type NativeShell = 'android' | 'ios'

/** The Android app's package (quizbuks-mobile/android/twa-manifest.json `packageId`). */
export const ANDROID_PACKAGE = 'com.quizbuks.app'
/** What the iOS shell adds to the web view's user agent (capacitor.config.json `ios.appendUserAgent`). */
export const IOS_USER_AGENT = 'QuizBuksApp/ios'
const PARAM = 'shell'
const KEY = 'qb.shell'
const SHELLS: readonly NativeShell[] = ['android', 'ios']

const isShell = (value: string | null): value is NativeShell => SHELLS.includes(value as NativeShell)

function seen(win: Window): NativeShell | null {
  try {
    const param = new URL(win.location.href).searchParams.get(PARAM)
    if (isShell(param)) return param
  } catch {
    // No address to read: the user agent and the referrer are left.
  }
  if (win.navigator.userAgent.includes(IOS_USER_AGENT)) return 'ios'
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
    const kept = win.sessionStorage.getItem(KEY)
    if (isShell(kept)) return kept
  } catch {
    // Blocked storage: what the page itself sees.
  }
  return seen(win)
}
