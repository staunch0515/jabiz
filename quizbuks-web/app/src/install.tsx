import { PwaInstallBanner } from '@jabiz/ui'
import { useEffect } from 'react'

/*
 * Offering to add the app to the home screen (docs/quizbuks/plans/Q38-install.md): after the first quiz is finished,
 * on the result page and from then on at the top of home, never before (an offer on the first visit is closed unread);
 * the account page is the place to install at any time. Closing it anywhere counts (one key, "taker"), so a closed
 * offer stays away 30 days and for good after three closes (platform 16zj).
 */

const APP_NAME = 'QuizBuks'
const ANSWERED = 'qb.answeredOnce'
const KEY = 'taker'

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
