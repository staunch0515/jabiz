/**
 * Where the browser may be sent for a payment step (Stripe's Checkout or Connect onboarding). The address comes from
 * the caller's own record, which the server wrote only after checking it against Stripe's address
 * (`quizbuks.stripe.checkout-url-prefix` / `connect-url-prefix`); the page checks again before leaving, so that no
 * value it reads can turn it into an open redirect or run script: only an absolute `https:` address without user
 * information, or, for a local Stripe stub (development and the end-to-end tests, as the server allows), plain
 * `http:` on this machine while the page itself is served from this machine.
 */

const LOOPBACK = new Set(['localhost', '127.0.0.1', '[::1]'])

/** The longest address a record keeps (`QbTopUps.URL_LENGTH`). */
const MAX_LENGTH = 2048

/** The address to go to, or null when it must not be followed. */
export function stripePageUrl(value: unknown, here: { hostname: string } = window.location): string | null {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_LENGTH) return null
  let url: URL
  try {
    // No base: a relative or scheme-less value is refused rather than resolved against this site.
    url = new URL(value)
  } catch {
    return null
  }
  if (url.username || url.password) return null
  if (url.protocol === 'https:') return url.href
  if (url.protocol === 'http:' && LOOPBACK.has(url.hostname) && LOOPBACK.has(here.hostname)) return url.href
  return null
}

/** Leaves for the payment step (a whole-page navigation; the browser comes back to the return address). */
export function goToStripe(url: string) {
  window.location.assign(url)
}
