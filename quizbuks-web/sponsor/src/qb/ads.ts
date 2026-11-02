/**
 * Google AdSense (Auto ads): the publisher's loader, as AdSense gives it, added to the page's head once. Google then
 * places ads where its Auto ads settings allow; which pages and formats is set in the AdSense account (exclude the
 * sign-in pages there: AdSense allows no ads on screens without content). Ads are served only on the sites approved in
 * the account, so a staging host shows none. Not inside the store apps' shells: AdSense does not allow its ads in an
 * app's web view without Google's in-app integration (the app calls `loadAds({ skip: nativeShell() !== null })`).
 * Nor in automated browsers (`navigator.webdriver`: the e2e tests and the store screenshots), whose views Google
 * counts as invalid traffic.
 */
export const ADSENSE_CLIENT = 'ca-pub-8399814237019515'
export const ADSENSE_SCRIPT = 'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js'

/** Adds the AdSense loader unless `skip` or it is there already; whether it was added. */
export function loadAds({ skip = false, doc = document }: { skip?: boolean; doc?: Document } = {}): boolean {
  if (skip || doc.defaultView?.navigator.webdriver || doc.querySelector(`script[src^="${ADSENSE_SCRIPT}"]`)) return false
  const script = doc.createElement('script')
  script.async = true
  script.src = `${ADSENSE_SCRIPT}?client=${ADSENSE_CLIENT}`
  script.crossOrigin = 'anonymous'
  doc.head.appendChild(script)
  return true
}
