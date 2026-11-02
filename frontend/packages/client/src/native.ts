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
