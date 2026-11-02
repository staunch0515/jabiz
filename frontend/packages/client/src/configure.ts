import { configureSession } from './api/session'
import { forgetEntryInfo } from './auth/account'
import { configureEnvironment, type EnvironmentOverride } from './auth/environment'
import {
  configureNativePush, configureNativeShell, configureOpenAuthorization, type NativePush, type OpenAuthorization,
} from './native'

/** What a frontend tells `@jabiz/client` about itself (decision D43). */
export interface ClientOptions {
  /**
   * The sign-in entry of this frontend (`jabiz.security.entries.<name>`): signing in, refreshing, identity providers
   * and the stored session are the entry's. The default is the build's `VITE_JABIZ_ENTRY`, else `admin`.
   */
  entry?: string
  /**
   * How identity providers' pages open (phase 17l, decision D68): a native shell hands them to the system's browser,
   * as providers refuse embedded web views. Absent: unchanged; `null`: the page itself navigates (the default).
   */
  openAuthorization?: OpenAuthorization | null
  /**
   * The native shell the frontend runs in (`ios`, `android`, …), or `null` in a browser: the PWA's install prompts stay
   * hidden in a shell. Absent: unchanged.
   */
  nativeShell?: string | null
  /**
   * Push notifications through the native shell (phase 17k-b): an iOS shell hands over Apple's device token, which the
   * platform registers as the device. Absent: unchanged; `null`: the browser's Web Push (the default).
   */
  nativePush?: NativePush | null
  /**
   * The application's say over the environment its pages show (phase 17ze): given what the server said
   * (`jabiz.web.environment`) and the page's host name, the environment to show instead, or nothing to keep the
   * server's. For a public site answered for a while by a test system's application. Absent: unchanged; `null`: the
   * server's (the default).
   */
  environment?: EnvironmentOverride | null
}

/** Configures the client; call it once before the first render (before `AuthProvider` reads the session). */
export function configureClient(options: ClientOptions) {
  if (options.entry !== undefined) {
    configureSession(options.entry)
    forgetEntryInfo()
  }
  if (options.openAuthorization !== undefined) configureOpenAuthorization(options.openAuthorization)
  if (options.nativeShell !== undefined) configureNativeShell(options.nativeShell)
  if (options.nativePush !== undefined) configureNativePush(options.nativePush)
  if (options.environment !== undefined) configureEnvironment(options.environment)
}
