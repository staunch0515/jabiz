import { installOfferAllowed, readInstallDismissals, useInstallPrompt, type InstallSituation } from '@jabiz/client'
import { PwaInstallBanner } from '@jabiz/ui'
import { useEffect, useState } from 'react'
import { nativeShell } from './shell'
import { BRAND } from './qb/brand'

/*
 * Offering to add the app to the home screen (docs/quizbuks/plans/Q38-install.md): after the first quiz is finished,
 * on the result page and from then on at the top of home, never before (an offer on the first visit is closed unread);
 * the account page is the place to install at any time. Closing it anywhere counts (one key, "taker"), so a closed
 * offer stays away 30 days and for good after three closes (platform 16zj). In a store app (Q49b, shell.ts) there is
 * nothing to install: no offer anywhere, the account page included.
 *
 * Inside another app's browser (WeChat, QQ, LINE) installing is impossible and the sign-in stays in that app, so the
 * way out to the phone's browser shows from the first visit, on the sign-in pages and a share link's page (Q50,
 * amending D-Q38-1); the steps for WeChat and QQ are the platform's (17m).
 */

// The name it is installed under: the manifest's, one for every language (Q61).
const APP_NAME = BRAND.name
/**
 * Set once the app has run installed on this browser (Q41): the business site (same origin, same storage) then lets its
 * "start answering" link open normally, so a browser that captures links into an installed app (desktop Chrome 139+)
 * opens the app's own window instead of a phone-sized popup. It is never cleared: after an uninstall the link opens a
 * tab, which is what a link does anyway.
 */
export const INSTALLED = 'qb.app.installed'
const ANSWERED = 'qb.answeredOnce'
const KEY = 'taker'

function markInstalled(win: Window) {
  try {
    win.localStorage.setItem(INSTALLED, 'true')
  } catch {
    // Blocked storage: the business site keeps its phone-sized window.
  }
}

function runsStandalone(win: Window): boolean {
  try {
    return win.matchMedia('(display-mode: standalone)').matches
      || (win.navigator as Navigator & { standalone?: boolean }).standalone === true
  } catch {
    return false
  }
}

/** Notes, for the business site, that the app runs installed now or gets installed while open (Q41). */
export function rememberInstalled(win: Window = window) {
  if (runsStandalone(win)) markInstalled(win)
  win.addEventListener('appinstalled', () => markInstalled(win))
}

/** Whether this browser has seen a finished quiz of the taker's (an offer before that is too early). */
export function answeredOnce(): boolean {
  try {
    return window.localStorage.getItem(ANSWERED) === 'true'
  } catch {
    return false
  }
}

function rememberAnswered() {
  try {
    window.localStorage.setItem(ANSWERED, 'true')
  } catch {
    // Blocked storage: home does not offer, the result page and the account page still do.
  }
}

/** The offer on a finished quiz's result: from now on home offers it too. */
export function ResultInstallOffer() {
  useEffect(rememberAnswered, [])
  if (nativeShell()) return null
  return <PwaInstallBanner appName={APP_NAME} rememberAs={KEY} />
}

/** The offer at the top of home, once a quiz was finished on this browser. */
export function HomeInstallOffer() {
  if (!answeredOnce() || nativeShell()) return null
  return <PwaInstallBanner appName={APP_NAME} rememberAs={KEY} />
}

/** The account page's permanent entry: no close button; says so once installed. Nothing in a store app. */
export function AccountInstallEntry() {
  if (nativeShell()) return null
  return <PwaInstallBanner appName={APP_NAME} rememberAs={KEY} dismissible={false} />
}

/** "Open this in your browser", from the first visit, inside another app's browser only (Q50). */
export function InAppBrowserNotice() {
  const { situation } = useInstallPrompt()
  if (situation !== 'in-app' || nativeShell()) return null
  return <PwaInstallBanner appName={APP_NAME} rememberAs={KEY} />
}

/**
 * Whether the note inside another app's browser shows now (Q50): in such a browser, not in a store app, and not closed
 * within the banner's limits (30 days, for good after three). Its closes are its own record (`taker.in-app`, platform
 * 17zk gap 11), or until it has one, the shared record the banner carries over from before.
 */
export function inAppNoticeOpen(situation: InstallSituation, now: number = Date.now()): boolean {
  if (situation !== 'in-app' || nativeShell()) return false
  return installOfferAllowed(readInstallDismissals(`${KEY}.in-app`) ?? readInstallDismissals(KEY), now)
}

/**
 * The in-app note for the entry pages' notices: whether it shows (an `active` notice takes the one expanded place on
 * a phone, so a closed note must give it up) and `recheck`, to call after a click inside it (the banner keeps its
 * closes in storage, which tells nobody).
 */
export function useInAppNotice(): { open: boolean; recheck: () => void } {
  const { situation } = useInstallPrompt()
  const [, setChecks] = useState(0)
  return { open: inAppNoticeOpen(situation), recheck: () => setChecks((n) => n + 1) }
}
