/**
 * `@jabiz/admin`: what an application's admin extension may use of the platform's frontend (decision D22). This file
 * is a contract: extensions import nothing else from the platform, and a change that breaks what is exported here is
 * an incompatible change of the platform (a new version line, decision D21).
 */

// The extension itself.
export { defineExtension, EXTENSION_NAMESPACE } from '../extension/api'
export type { AdminExtension, ExtensionMenuItem } from '../extension/api'

// Calling the server: typed platform endpoints, SQL templates and processes, all with the session and the language.
export { api, unwrap, sessionFetch, ApiError } from '../api/client'
export { runQuery, runProcess } from './calls'
export type { QueryPage, QueryOptions, ProcessOptions, Filter, Sort } from './calls'
export type { components as ApiSchemas } from '../api/schema'
export { uploadFile, fetchFileContent, fetchFileDownload } from '../api/files'

// Who is signed in; `can` only decides what the UI offers, the server checks every call.
export { useAuth } from '../auth/AuthContext'

// Metadata and its presentation.
export {
  useLanguage,
  useMe,
  useDatasets,
  useDataset,
  useEntityMeta,
  useProcesses,
  useDictionaries,
  useMyTasks,
} from '../meta/hooks'
export type { MyTask } from '../meta/hooks'
export type { EntityMeta, FieldMeta, DatasetEntry, ProcessEntry, EntityInstance, DictItem, Violation } from '../meta/types'
export { fieldLabel, formatValue } from '../meta/kinds'
export { parseDecimal, toDecimal, formatDecimal, compareDecimal } from '../meta/decimal'
export { formatAmount, formatDate, formatDateTime, displayLocale, appRegion } from '../meta/format'
export type { AmountFormat } from '../meta/format'
export { enabledLanguages } from '../i18n/languages'
export type { Decimal } from '../meta/decimal'
export { paths } from '../pages/paths'

// Building blocks of the generated pages.
export { default as EntityFormDrawer } from '../components/EntityFormDrawer'
export { default as ReferenceSelect } from '../components/ReferenceSelect'
export { default as FieldErrors } from '../components/FieldErrors'
export { default as FilePreview } from '../components/FilePreview'
export { default as MarkdownView } from '../components/MarkdownView'
export { default as ApprovalPanel } from '../components/ApprovalPanel'
export type { ApprovalPanelProps } from '../components/ApprovalPanel'
export { default as DocumentPanel } from '../components/DocumentPanel'
export type { DocumentPanelProps } from '../components/DocumentPanel'
export { default as UserName, useUserName } from '../components/UserName'
export type { UserNameProps } from '../components/UserName'
