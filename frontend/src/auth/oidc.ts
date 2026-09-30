import { api, unwrap } from '../api/client'

/** Where to go after signing in through an identity provider; kept across the round trip (not a secret). */
const RETURN_KEY = 'jabiz.oidcReturnTo'
/**
 * The secret binding the round trip to this browser: the server gives it to the page that started the sign-in and
 * refuses the callback without it, so a state and code sent to someone else sign nobody in (login CSRF).
 */
const BINDER_KEY = 'jabiz.oidcBinder'

/**
 * Starts signing in through an identity provider (docs/design/10-security.md section 12): the server records the
 * request and names the provider's page, which the browser then opens. The provider sends the user back to
 * `/login/oidc`.
 */
export async function startProviderSignIn(providerId: string, returnTo: string): Promise<void> {
  const started = await unwrap(api.POST('/api/auth/oidc/{id}/start', { params: { path: { id: providerId } } }))
  try {
    window.sessionStorage.setItem(RETURN_KEY, returnTo)
    window.sessionStorage.setItem(BINDER_KEY, started.binder!)
  } catch {
    // Without storage the callback is refused: the binder cannot come back.
  }
  window.location.assign(started.authorizationUrl!)
}

/** The binder of the sign-in this browser started, once. */
export function takeBinder(): string | null {
  try {
    const binder = window.sessionStorage.getItem(BINDER_KEY)
    window.sessionStorage.removeItem(BINDER_KEY)
    return binder
  } catch {
    return null
  }
}

/** The page to go to after the round trip, once; only paths of this application. */
export function takeReturnPath(): string {
  let path: string | null = null
  try {
    path = window.sessionStorage.getItem(RETURN_KEY)
    window.sessionStorage.removeItem(RETURN_KEY)
  } catch {
    // nothing stored
  }
  return path && path.startsWith('/') && !path.startsWith('//') ? path : '/data'
}
