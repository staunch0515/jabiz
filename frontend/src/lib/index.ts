/**
 * `@jabiz/admin`: what an application's admin extension may use of the platform's frontend (decision D22). This file
 * is a contract: extensions import nothing else from the platform, and a change that breaks what is exported here is
 * an incompatible change of the platform (a new version line, decision D21). Since line 1.2 it stands on the two
 * frontend packages (decision D34): the headless parts of @jabiz/client and all of @jabiz/ui.
 */

// The extension itself.
export { defineExtension, EXTENSION_NAMESPACE } from '../extension/api'
export type { AdminExtension, ExtensionMenuItem } from '../extension/api'

// Calling the server: typed platform endpoints, SQL templates and processes, all with the session and the language.
export { api, unwrap, sessionFetch, ApiError, runQuery, runProcess } from '@jabiz/client'
export type { QueryPage, QueryOptions, ProcessOptions, Filter, Sort } from '@jabiz/client'
export type { components as ApiSchemas } from '@jabiz/client'
export { uploadFile, fetchFileContent, fetchFileDownload } from '@jabiz/client'

// Who is signed in; `can` only decides what the UI offers, the server checks every call.
export { useAuth } from '@jabiz/client'

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
export { parseDecimal, toDecimal, formatDecimal, compareDecimal } from '@jabiz/client'
export { formatAmount, formatDate, formatDateTime, displayLocale, appRegion } from '@jabiz/client'
export type { AmountFormat } from '@jabiz/client'
export { enabledLanguages } from '@jabiz/client'
export type { Decimal } from '@jabiz/client'
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
export { UserName, useUserName } from '@jabiz/client'
export type { UserNameProps } from '@jabiz/client'

// The components and the theme's helpers (shadcn/ui and the platform's composites: DataTable, ConfirmDialog,
// notify, DatePicker, MoneyInput, PageHeader, ...). Ant Design is no longer offered to extensions (phase 15a).
export * from '@jabiz/ui'
