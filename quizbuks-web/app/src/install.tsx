import { PwaInstallBanner } from '@jabiz/ui'
import { useEffect } from 'react'

/*
 * Offering to add the app to the home screen (docs/quizbuks/plans/Q38-install.md): after the first quiz is finished,
 * on the result page and from then on at the top of home, never before (an offer on the first visit is closed unread);
 * the account page is the place to install at any time. Closing it anywhere counts (one key, "taker"), so a closed
 * offer stays away 30 days and for good after three closes (platform 16zj).
 */

const APP_NAME = 'QuizBuks'
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
  return <PwaInstallBanner appName={APP_NAME} rememberAs={KEY} />
}

/** The offer at the top of home, once a quiz was finished on this browser. */
export function HomeInstallOffer() {
  if (!answeredOnce()) return null
  return <PwaInstallBanner appName={APP_NAME} rememberAs={KEY} />
}

/** The account page's permanent entry: no close button; says so once installed. */
export function AccountInstallEntry() {
  return <PwaInstallBanner appName={APP_NAME} rememberAs={KEY} dismissible={false} />
}
