// Moved to @jabiz/client (decision D34). The admin frontend keeps importing this path, and its tests keep mocking it,
// until its pages move to @jabiz/ui (phases 15b, 15c).
export { regionOf, appRegion, displayLocale, formatDateTime, formatDate, formatAmount, setAmountUnit } from '@jabiz/client'
export type { AmountFormat } from '@jabiz/client'
