import { configureSession } from './api/session'
import { forgetEntryInfo } from './auth/account'

/** What a frontend tells `@jabiz/client` about itself (decision D43). */
export interface ClientOptions {
  /**
   * The sign-in entry of this frontend (`jabiz.security.entries.<name>`): signing in, refreshing, identity providers
   * and the stored session are the entry's. The default is the build's `VITE_JABIZ_ENTRY`, else `admin`.
   */
  entry?: string
}

/** Configures the client; call it once before the first render (before `AuthProvider` reads the session). */
export function configureClient(options: ClientOptions) {
  if (options.entry !== undefined) {
    configureSession(options.entry)
    forgetEntryInfo()
  }
}
