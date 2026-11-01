import { api, unwrap } from '../api/client'
import { session } from '../api/session'
import { sitePath } from './mailLink'
import { openAuthorization } from '../native'

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
 * sends the user back to the entry's callback page (`login/oidc`). A native shell opens the provider's page in the
 * system's browser (`configureClient({ openAuthorization })`, decision D68).
 */
export async function startProviderSignIn(providerId: string, returnTo: string): Promise<void> {
  const started = await unwrap(api.POST('/api/auth/oidc/{id}/start', {
    params: { path: { id: providerId }, query: { entry: session.entry() } },
  }))
  keep(returnTo, started.binder!, 'SIGN_IN')
  await openAuthorization(started.authorizationUrl!)
}

/**
 * What the round trip is for: signing in, or linking a provider account to the signed-in user (phase 16f). The
 * callback page reads it to know which server endpoint gets the code; the server checks the purpose of the state
 * itself, so a wrong one here only fails.
 */
export type OidcPurpose = 'SIGN_IN' | 'LINK'
const purposeKey = () => `jabiz.session.${session.entry()}.oidcPurpose`

/** What {@link startIdentityLink} needs besides the provider. */
export interface IdentityLinkOptions {
  /**
   * The current password; needed unless the session passed a second factor within the server's step-up age. Without
   * either the server answers 422 `REAUTHENTICATION_REQUIRED`, and a wrong password 422 `PASSWORD_INCORRECT`.
   */
  currentPassword?: string
}

function keep(returnTo: string, binder: string, purpose: OidcPurpose) {
  try {
    window.sessionStorage.setItem(returnKey(), sitePath(returnTo))
    window.sessionStorage.setItem(binderKey(), binder)
    window.sessionStorage.setItem(purposeKey(), purpose)
  } catch {
    // Without storage the callback is refused: the binder cannot come back.
  }
}

/**
 * Starts linking an account of an identity provider to the signed-in user (`POST /api/auth/account/identities/start`,
 * phase 16f; decision D39 item 2). The provider sends the user back to the entry's callback page (`login/oidc`), which
 * finishes the link (`finishIdentityLink`) and returns to `returnTo`. Throws the server's refusal (an
 * `ApiError`) without leaving the page.
 */
export async function startIdentityLink(providerId: string, options: IdentityLinkOptions, returnTo: string):
Promise<void> {
  const started = await unwrap(api.POST('/api/auth/account/identities/start', {
    body: { provider: providerId, currentPassword: options.currentPassword || undefined },
  }))
  keep(returnTo, started.binder!, 'LINK')
  await openAuthorization(started.authorizationUrl!)
}

/**
 * What the latest round trip this browser started is for; a sign-in when nothing was kept. Read, not taken: every
 * start writes it anew (and the callback page may render more than once).
 */
export function pendingPurpose(): OidcPurpose {
  try {
    return window.sessionStorage.getItem(purposeKey()) === 'LINK' ? 'LINK' : 'SIGN_IN'
  } catch {
    return 'SIGN_IN'
  }
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
