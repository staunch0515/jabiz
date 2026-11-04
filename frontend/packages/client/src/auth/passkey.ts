import { api, unwrap } from '../api/client'
import type { components } from '../api/schema'
import { session } from '../api/session'
import { nativeShell } from '../native'
import { inAppBrowser } from '../pwa/install'

/**
 * Passkeys (decision D71; docs/design/10-security.md section 15.16): the browser side of the ceremonies the platform
 * runs — the options it hands out are WebAuthn's JSON, turned into what `navigator.credentials` takes, and the
 * browser's answer is turned back into JSON. Passkeys are an extra way in for existing accounts: pages offer them only
 * where the entry takes them (`entryInfo().passkeys`) and {@link passkeySupport} says the browser can.
 */

/** One of the signed-in user's passkeys. */
export type Passkey = components['schemas']['PasskeyView']

/** The server's options for `navigator.credentials` (`publicKey`), as WebAuthn's JSON. */
type OptionsJson = Record<string, unknown>

/**
 * Whether this browser can use passkeys here: `available`; `in-app-browser` inside WeChat, QQ or another app's web view
 * (they do not offer passkeys: open the page in the browser); `unsupported` without WebAuthn.
 */
export type PasskeySupport = 'available' | 'in-app-browser' | 'unsupported'

/** What {@link passkeySupport} looks at; the page's own by default (tests pass theirs). */
export interface PasskeyEnvironment {
  userAgent: string
  webAuthn: boolean
  /** The page runs in the store app's own shell, which declares its domain to the system (decision D68). */
  nativeShell: boolean
}

function environment(): PasskeyEnvironment {
  const hasWindow = typeof window !== 'undefined'
  return {
    userAgent: typeof navigator === 'undefined' ? '' : navigator.userAgent,
    webAuthn: hasWindow && typeof window.PublicKeyCredential === 'function'
      && typeof navigator !== 'undefined' && !!navigator.credentials,
    nativeShell: nativeShell() !== null,
  }
}

/** Whether passkeys can be used in this browser (pure for a given environment). */
export function passkeySupport(env: PasskeyEnvironment = environment()): PasskeySupport {
  if (!env.nativeShell && inAppBrowser(env.userAgent)) return 'in-app-browser'
  return env.webAuthn ? 'available' : 'unsupported'
}

/** Whether the browser offers passkeys in the name field's suggestions (conditional mediation). */
export async function passkeyAutofill(): Promise<boolean> {
  if (passkeySupport() !== 'available') return false
  const check = (window.PublicKeyCredential as unknown as { isConditionalMediationAvailable?: () => Promise<boolean> })
    .isConditionalMediationAvailable
  try {
    return typeof check === 'function' ? await check.call(window.PublicKeyCredential) : false
  } catch {
    return false
  }
}

// ---- WebAuthn's JSON ------------------------------------------------------------------------------------------

/** Base64url (no padding) to bytes. */
export function fromBase64url(text: string): ArrayBuffer {
  const base64 = text.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((text.length + 3) % 4)
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes.buffer
}

/** Bytes to Base64url without padding. */
export function toBase64url(buffer: ArrayBuffer | ArrayBufferView): string {
  const bytes = buffer instanceof ArrayBuffer ? new Uint8Array(buffer)
    : new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

type Descriptor = { type: string; id: string; transports?: string[] }

function descriptors(list: unknown): PublicKeyCredentialDescriptor[] {
  return ((list as Descriptor[] | undefined) ?? []).map((d) => ({
    type: 'public-key',
    id: fromBase64url(d.id),
    ...(d.transports ? { transports: d.transports as AuthenticatorTransport[] } : {}),
  }))
}

/** The creation options as `navigator.credentials.create` takes them. */
export function creationOptions(json: OptionsJson): PublicKeyCredentialCreationOptions {
  const user = json.user as { id: string; name: string; displayName: string }
  return {
    ...(json as unknown as PublicKeyCredentialCreationOptions),
    challenge: fromBase64url(json.challenge as string),
    user: { ...user, id: fromBase64url(user.id) },
    excludeCredentials: descriptors(json.excludeCredentials),
  }
}

/** The request options as `navigator.credentials.get` takes them. */
export function requestOptions(json: OptionsJson): PublicKeyCredentialRequestOptions {
  return {
    ...(json as unknown as PublicKeyCredentialRequestOptions),
    challenge: fromBase64url(json.challenge as string),
    allowCredentials: descriptors(json.allowCredentials),
  }
}

/** The browser's answer as WebAuthn's JSON (`PublicKeyCredential.toJSON()`, written out where it is missing). */
export function credentialJson(credential: PublicKeyCredential): Record<string, unknown> {
  const native = (credential as unknown as { toJSON?: () => Record<string, unknown> }).toJSON
  if (typeof native === 'function') return native.call(credential)
  const response = credential.response as AuthenticatorResponse & Partial<AuthenticatorAttestationResponse>
    & Partial<AuthenticatorAssertionResponse>
  const json: Record<string, unknown> = { clientDataJSON: toBase64url(response.clientDataJSON) }
  if (response.attestationObject) {
    json.attestationObject = toBase64url(response.attestationObject)
    json.transports = response.getTransports?.() ?? []
  }
  if (response.authenticatorData) json.authenticatorData = toBase64url(response.authenticatorData)
  if (response.signature) json.signature = toBase64url(response.signature)
  if (response.userHandle) json.userHandle = toBase64url(response.userHandle)
  return {
    id: credential.id,
    rawId: toBase64url(credential.rawId),
    type: credential.type,
    response: json,
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
    clientExtensionResults: credential.getClientExtensionResults?.() ?? {},
  }
}

/** Thrown when the person closed the system's dialog or the browser refused (nothing was sent). */
export class PasskeyCancelledError extends Error {
  constructor(cause?: unknown) {
    super('The passkey dialog was closed or refused', { cause })
    this.name = 'PasskeyCancelledError'
  }
}

async function getAnswer(json: OptionsJson, mediation?: CredentialMediationRequirement, signal?: AbortSignal) {
  let credential: Credential | null
  try {
    credential = await navigator.credentials.get({ publicKey: requestOptions(json), mediation, signal })
  } catch (e) {
    throw new PasskeyCancelledError(e)
  }
  if (!credential) throw new PasskeyCancelledError()
  return credentialJson(credential as PublicKeyCredential)
}

async function createAnswer(json: OptionsJson) {
  let credential: Credential | null
  try {
    credential = await navigator.credentials.create({ publicKey: creationOptions(json) })
  } catch (e) {
    throw new PasskeyCancelledError(e)
  }
  if (!credential) throw new PasskeyCancelledError()
  return credentialJson(credential as PublicKeyCredential)
}

const publicKey = (answer: { publicKey?: Record<string, unknown> }) => answer.publicKey ?? {}

// ---- sign-in, second step, enrolment, step-up -------------------------------------------------------------------

/** The answer of a passkey sign-in: tokens, or (never for passkeys, but typed alike) a challenge. */
export type PasskeyTokens = components['schemas']['TokenResponse']

export interface PasskeySignInOptions {
  /** Offer the passkeys in the name field's suggestions and wait (conditional mediation) instead of a dialog. */
  autofill?: boolean
  signal?: AbortSignal
}

/**
 * Signs in with any of the user's passkeys at the frontend's entry: the tokens. `AuthState.signInWithPasskey` keeps
 * them as the session; call this one only to handle them yourself.
 */
export async function passkeySignIn(options: PasskeySignInOptions = {}): Promise<PasskeyTokens> {
  const entry = session.entry()
  const asked = await unwrap(api.POST('/api/auth/passkey/options', { body: { entry } }))
  const credential = await getAnswer(publicKey(asked), options.autofill ? 'conditional' : undefined, options.signal)
  return unwrap(api.POST('/api/auth/passkey', { body: { entry, credential } }))
}

/** The second step after a password with one of the challenge's user's passkeys: the tokens. */
export async function passkeySecondStep(challenge: string): Promise<PasskeyTokens> {
  const asked = await unwrap(api.POST('/api/auth/challenge/passkey/options', { body: { challenge } }))
  const credential = await getAnswer(publicKey(asked))
  return unwrap(api.POST('/api/auth/challenge/passkey', { body: { challenge, credential } }))
}

/**
 * Registers a passkey instead of TOTP under the enrolment challenge of a role that requires a second factor; the user
 * signs in again afterwards.
 */
export async function enrolPasskey(challenge: string, name?: string): Promise<void> {
  const asked = await unwrap(api.POST('/api/auth/challenge/passkey/register/options', { body: { challenge } }))
  const credential = await createAnswer(publicKey(asked))
  await unwrap(api.POST('/api/auth/challenge/passkey/register', { body: { challenge, credential, name } }))
}

/** Confirms the signed-in user with a passkey for an operation that requires a recent second factor. */
export async function stepUpWithPasskey(): Promise<void> {
  const asked = await unwrap(api.POST('/api/auth/step-up/passkey/options'))
  const credential = await getAnswer(publicKey(asked))
  const answer = await unwrap(api.POST('/api/auth/step-up/passkey', { body: { credential } }))
  session.storeAccess(answer.accessToken!)
}

// ---- the signed-in user's own passkeys --------------------------------------------------------------------------

/** The signed-in user's passkeys, oldest first. */
export async function listPasskeys(): Promise<Passkey[]> {
  return unwrap(api.GET('/api/auth/account/passkeys'))
}

/**
 * How the user proves who they are before adding a passkey: nothing after a recent second factor, the current password,
 * or the token of the confirmation mail (users without a password, {@link requestPasskeyConfirmation}).
 */
export interface PasskeyProof {
  currentPassword?: string
  passkeyToken?: string
}

/**
 * Adds a passkey to the signed-in user's account: the options (after the proof), the system's dialog, the passkey's
 * id. Refused with 422 REAUTHENTICATION_REQUIRED (ask for the password), REAUTHENTICATION_UNAVAILABLE (no password:
 * ask for the confirmation mail), PASSKEY_LIMIT, PASSKEY_EXISTS; {@link PasskeyCancelledError} when the dialog was
 * closed.
 */
export async function addPasskey(proof: PasskeyProof = {}, name?: string): Promise<string> {
  const asked = await unwrap(api.POST('/api/auth/account/passkeys/options', { body: proof }))
  const credential = await createAnswer(publicKey(asked))
  const added = await unwrap(api.POST('/api/auth/account/passkeys', { body: { credential, name } }))
  return added.passkeyId!
}

/** Mails the signed-in user the link that confirms them before a passkey is added (users without a password). */
export async function requestPasskeyConfirmation(): Promise<void> {
  await unwrap(api.POST('/api/auth/account/passkeys/confirmation'))
}

export async function renamePasskey(passkeyId: string, name: string): Promise<void> {
  await unwrap(api.POST('/api/auth/account/passkeys/{passkeyId}/rename', {
    params: { path: { passkeyId } }, body: { name },
  }))
}

export async function removePasskey(passkeyId: string): Promise<void> {
  await unwrap(api.POST('/api/auth/account/passkeys/{passkeyId}/remove', { params: { path: { passkeyId } } }))
}
