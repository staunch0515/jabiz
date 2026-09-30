import { api, unwrap } from '../api/client'

/** Where to go after signing in through an identity provider; kept across the round trip (not a secret). */
const RETURN_KEY = 'jabiz.oidcReturnTo'

/**
 * Starts signing in through an identity provider (docs/design/10-security.md section 12): the server records the
 * request and names the provider's page, which the browser then opens. The provider sends the user back to
 * `/login/oidc`.
 */
export async function startProviderSignIn(providerId: string, returnTo: string): Promise<void> {
  const started = await unwrap(api.POST('/api/auth/oidc/{id}/start', { params: { path: { id: providerId } } }))
  try {
    window.sessionStorage.setItem(RETURN_KEY, returnTo)
  } catch {
    // Without storage the user lands on the home page.
  }
  window.location.assign(started.authorizationUrl!)
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
