/**
 * `@jabiz/ui`: the components of jabiz frontends (decision D34): shadcn/ui's components (generated into this
 * package only; applications and extensions do not keep copies), the platform's composite components and the theme
 * (`@jabiz/ui/theme.css`), and the sign-in and account building blocks (`auth/`). Texts are in the i18next namespaces
 * `ui` and `auth`, in every platform language.
 */
import './i18n'

export { cn, UI_SCOPE } from './lib/utils'
export { UI_NAMESPACE, uiMessages } from './i18n'
export { useIsMobile } from './hooks/use-mobile'
export * from './form-state'

// shadcn/ui
export * from './components/ui/alert'
export * from './components/ui/alert-dialog'
export * from './components/ui/avatar'
export * from './components/ui/badge'
export * from './components/ui/breadcrumb'
export * from './components/ui/button'
export * from './components/ui/calendar'
export * from './components/ui/card'
export * from './components/ui/chart'
export * from './components/ui/checkbox'
export * from './components/ui/command'
export * from './components/ui/dialog'
export * from './components/ui/dropdown-menu'
export * from './components/ui/form'
export * from './components/ui/input'
export * from './components/ui/label'
export * from './components/ui/pagination'
export * from './components/ui/popover'
export * from './components/ui/radio-group'
export * from './components/ui/scroll-area'
export * from './components/ui/select'
export * from './components/ui/separator'
export * from './components/ui/sheet'
export * from './components/ui/sidebar'
export * from './components/ui/skeleton'
export * from './components/ui/sonner'
export * from './components/ui/switch'
export * from './components/ui/table'
export * from './components/ui/tabs'
export * from './components/ui/textarea'
export * from './components/ui/toggle'
export * from './components/ui/toggle-group'
export * from './components/ui/tooltip'

// The platform's composite components.
export {
  APPEARANCES,
  AppearanceProvider,
  applyAppearance,
  resolveAppearance,
  storedAppearance,
  useAppearance,
} from './components/appearance'
export type { Appearance } from './components/appearance'
export { ThemeToggle } from './components/theme-toggle'
export { DataTable } from './components/data-table'
export type {
  DataTableProps,
  ColumnDef,
  ColumnFiltersState,
  PaginationState,
  SortingState,
} from './components/data-table'
export { ConfirmDialog } from './components/confirm-dialog'
export type { ConfirmDialogProps } from './components/confirm-dialog'
export { notify } from './components/notify'
export type { NotifyOptions } from './components/notify'
export {
  DatePicker,
  DateTimePicker,
  formatDateTimeText,
  parseDateText,
  parseDateTimeText,
  parseDateValue,
  toDateValue,
} from './components/date-picker'
export type { DatePickerProps, DateTimePickerProps } from './components/date-picker'
export {
  DecimalInput,
  MoneyInput,
  acceptsDecimalText,
  normalizeDecimalText,
} from './components/decimal-input'
export type { DecimalInputProps, MoneyInputProps } from './components/decimal-input'
export { PageHeader } from './components/page-header'
export type { PageHeaderProps } from './components/page-header'
export { AppShell, ShellNav } from './components/app-shell'
export { Combobox } from './components/combobox'
export type { ComboboxOption, ComboboxProps } from './components/combobox'
export { FilterBar, filledFilters } from './components/filter-bar'
export type { FilterBarProps, FilterField, FilterRange, FilterValue, FilterValues } from './components/filter-bar'
export { FileUpload, fileMatchesAccept } from './components/file-upload'
export type { FileUploadProps } from './components/file-upload'
export { Timeline } from './components/timeline'
export type { TimelineItem, TimelineProps, TimelineTone } from './components/timeline'
export { DescriptionList } from './components/description-list'
export type { DescriptionItem, DescriptionListProps } from './components/description-list'
export { TagsInput } from './components/tags-input'
export type { TagsInputProps } from './components/tags-input'
export { CopyButton } from './components/copy-button'
export type { CopyButtonProps } from './components/copy-button'
export { PageState } from './components/page-state'
export type { PageStateKind, PageStateProps } from './components/page-state'
export { Spinner } from './components/spinner'
export type { SpinnerProps } from './components/spinner'
export { Steps } from './components/steps'
export type { StepItem, StepsProps } from './components/steps'
export { SegmentedControl } from './components/segmented-control'
export type { SegmentedControlProps, SegmentedOption } from './components/segmented-control'
export type { AppShellProps, ShellMenuItem, ShellNavProps, LinkProps as ShellLinkProps } from './components/app-shell'

// Signing in and the account (decision D43): pages and parts of pages, without a router, texts in namespace `auth`.
export * from './auth'
