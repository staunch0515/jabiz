// Moved to @jabiz/client (decision D34). The admin frontend keeps importing this path, and its tests keep mocking it,
// until its pages move to @jabiz/ui (phases 15b, 15c).
export { ApiError, toApiError } from '@jabiz/client'
export type { Violation } from '@jabiz/client'
