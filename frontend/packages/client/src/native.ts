/**
 * What a frontend running in a store app's native shell tells the platform (phase 17l, decision D68), through
 * `configureClient`: how to open an identity provider's page, and that it runs in a shell at all.
 */

/**
 * Opens an identity provider's authorization page. In a browser the page itself goes there; a native shell hands the
 * page to the system's browser (iOS `ASWebAuthenticationSession`, Android Custom Tabs), because providers such as
 * Google refuse to sign in inside an embedded web view. The provider sends the user back to the entry's callback page
 * (`login/oidc`), which the shell opens as an app link in its own web view: the binder kept there when the round trip
 * started is still there, so the callback works as in a browser.
 */
export type OpenAuthorization = (url: string) => void | Promise<void>

const inBrowser: OpenAuthorization = (url) => {
  window.location.assign(url)
}

let opener: OpenAuthorization = inBrowser
let shell: string | null = null

/** Sets how provider pages open; `null` restores the browser's own navigation. */
export function configureOpenAuthorization(open: OpenAuthorization | null) {
  opener = open ?? inBrowser
}

/** Names the native shell the frontend runs in (`ios`, `android`, …); `null` for a browser. */
export function configureNativeShell(name: string | null) {
  shell = name === null || name.trim() === '' ? null : name.trim()
}

/** Opens a provider's authorization page as configured. */
export function openAuthorization(url: string): Promise<void> {
  return Promise.resolve(opener(url))
}

/**
 * The native shell the frontend runs in, or `null` in a browser. In a shell the PWA's install prompts and guides stay
 * hidden: the app is installed already.
 */
export function nativeShell(): string | null {
  return shell
}

/**
 * Push notifications through the native shell (phase 17k-b, decision D69): an iOS shell's web view has no Web Push, so
 * the shell asks the system for permission and Apple's device token, and the platform registers the token for the
 * session as a device of kind `apns`. `enablePush`, `disablePush`, `pushEnabledHere` and the `PushPreferences` block
 * then use it instead of the browser's Push API. A tap on a notification is the shell's to handle: it opens the
 * notification's `url` in its web view.
 */
export interface NativePush {
  /** The kind of device the shell registers; Apple's push service for an iOS shell. */
  kind: 'apns'
  /** Asks the user's permission and returns this device's token (hexadecimal), or `null` when the user refused. */
  register(): Promise<string | null>
  /** This device's token, when the app has one already; `null` otherwise. */
  token(): Promise<string | null>
  /** Stops the app's notifications on this device (optional: the server forgets the token either way). */
  unregister?(): Promise<void>
}

let nativePushHandle: NativePush | null = null

/** Sets the shell's push notifications; `null` for a browser (the default). */
export function configureNativePush(push: NativePush | null) {
  nativePushHandle = push
}

/** The shell's push notifications, or `null` in a browser. */
export function nativePush(): NativePush | null {
  return nativePushHandle
}
