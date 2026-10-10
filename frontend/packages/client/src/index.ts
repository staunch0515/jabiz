/**
 * `@jabiz/client`: the headless part of a jabiz frontend (decision D34 item 4), shared by the admin frontend, its
 * extensions (through `@jabiz/admin`) and an application's own single-page applications. Signing in, the session and
 * its refresh, idle lock and step-up, the typed API client, processes and SQL templates, the interface language and
 * the formatting of amounts, dates and numbers. No component library: `@jabiz/ui` builds on this.
 */

// The server: typed endpoints and the session.
export {
  api,
  unwrap,
  sessionFetch,
  refreshSession,
  setApiLanguage,
  setStepUpHandler,
  stepUp,
  ApiError,
} from './api/client'
export { toApiError } from './api/problem'
export type { Violation } from './api/problem'
export { session } from './api/session'
export type { paths, components, operations } from './api/schema'
export {
  uploadFile,
  fetchFileContent,
  fetchFileDownload,
  fileNameOf,
  previewVariant,
  formatBytes,
} from './api/files'
export type { UploadedFile } from './api/files'
export { runQuery, runProcess } from './lib/calls'
export type { QueryPage, QueryOptions, ProcessOptions, Filter, Sort } from './lib/calls'

// Who is signed in.
export { AuthProvider, useAuth, lastUserName } from './auth/AuthContext'
export type { SignInStep, DataPeriod } from './auth/AuthContext'
export { useIdleLock } from './auth/useIdleLock'
export { startProviderSignIn, takeBinder, takeReturnPath } from './auth/oidc'
export { default as UserName, useUserName } from './components/UserName'
export type { UserNameProps } from './components/UserName'

// Language and formatting.
export { default as i18n, addMessages, changeLanguage, languages } from './i18n'
export { PLATFORM_LANGUAGES, LANGUAGE_NAMES, enabledLanguages, enabledLanguagesOf } from './i18n/languages'
export type { Language } from './i18n/languages'
export {
  parseDecimal,
  decimalFromNumber,
  toDecimal,
  compareDecimal,
  stripTrailingZeros,
  precision,
  signum,
  formatDecimal,
} from './meta/decimal'
export type { Decimal } from './meta/decimal'
export {
  regionOf,
  appRegion,
  displayLocale,
  formatDateTime,
  formatDate,
  formatAmount,
  setAmountUnit,
} from './meta/format'
export type { AmountFormat } from './meta/format'
