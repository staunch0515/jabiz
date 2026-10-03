import { formatAmount } from '@jabiz/admin'
import { assetPath } from '../assets/paths'
import { journalPath } from '../journal/paths'
import { billPath, runPath } from '../payables/paths'
import { invoicePath, receiptPath } from '../receivables/paths'
import type { DetailLine } from './api'

/** A statement figure: thousands separators, two decimals, negatives in parentheses (FIN-UI-005). */
export const figure = (value: unknown) => (value === null || value === undefined ? ''
  : formatAmount(value as number | string, { scale: 2, negative: 'parentheses' }))

/** The page of a posting's source document, where the finance application has one. */
export function documentPath(line: Pick<DetailLine, 'sourceEntity' | 'sourceId'>): string | undefined {
  if (!line.sourceId) return undefined
  switch (line.sourceEntity) {
    case 'FinJournal': return journalPath(line.sourceId)
    case 'FinBill': return billPath(line.sourceId)
    case 'FinInvoice': return invoicePath(line.sourceId)
    case 'FinReceipt': return receiptPath(line.sourceId)
    case 'FinPaymentRun': return runPath(line.sourceId)
    case 'FinAsset': return assetPath(line.sourceId)
    default: return undefined
  }
}
