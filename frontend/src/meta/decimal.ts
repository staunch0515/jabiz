// Moved to @jabiz/client (decision D34). The admin frontend keeps importing this path, and its tests keep mocking it,
// until its pages move to @jabiz/ui (phases 15b, 15c).
export {
  parseDecimal,
  decimalFromNumber,
  toDecimal,
  compareDecimal,
  stripTrailingZeros,
  precision,
  signum,
  formatDecimal,
} from '@jabiz/client'
export type { Decimal } from '@jabiz/client'
