import { api, unwrap } from '../api/client'
import { session } from '../api/session'
import { sitePath } from './mailLink'

/** Where to go after signing in through an identity provider; kept across the round trip (not a secret). */
const returnKey = () => `jabiz.session.${session.entry()}.oidcReturnTo`
/**
 * The secret binding the round trip to this browser: the server gives it to the page that started the sign-in and
 * refuses the callback without it, so a state and code sent to someone else sign nobody in (login CSRF).
 */
const binderKey = () => `jabiz.session.${session.entry()}.oidcBinder`

/**
 * Starts signing in through an identity provider (docs/design/10-security.md section 12) for this frontend's sign-in
 * entry: the server records the request and names the provider's page, which the browser then opens. The provider
 * sends the user back to the entry's callback page (`login/oidc`).
 */
export async function startProviderSignIn(providerId: string, returnTo: string): Promise<void> {
  const started = await unwrap(api.POST('/api/auth/oidc/{id}/start', {
    params: { path: { id: providerId }, query: { entry: session.entry() } },
  }))
  try {
    window.sessionStorage.setItem(returnKey(), returnTo)
    window.sessionStorage.setItem(binderKey(), started.binder!)
  } catch {
    // Without storage the callback is refused: the binder cannot come back.
  }
  window.location.assign(started.authorizationUrl!)
}

function take(key: string): string | null {
  try {
    const value = window.sessionStorage.getItem(key)
    window.sessionStorage.removeItem(key)
    return value
  } catch {
    return null
  }
}

/** The binder of the sign-in this browser started, once. */
export function takeBinder(): string | null {
  return take(binderKey())
}

/** The page to go to after the round trip, once; only paths of this site, else `fallback` (the frontend's home). */
export function takeReturnPath(fallback: string): string {
  return sitePath(take(returnKey()), fallback)
}
