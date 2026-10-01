import type { Suggestion } from './api'
import { add, cents, min, parseNumber, sign, stored, subtract, ZERO } from './money'
import type { Decimal } from '@jabiz/admin'

/**
 * Applying a receipt to open invoices (FIN-AR-007, 008) as data: what is typed per invoice, the amounts suggested
 * from the best matches, and what remains unapplied. The server checks every application again.
 */

/** What is typed for one invoice: the cash applied and the discount taken with it. */
export interface Entry {
  amount: string
  discount: string
}

export type Entries = Record<string, Entry>

/** An amount as typed in cents; null when blank, undefined when it cannot be read. */
export function readAmount(text: string): Decimal | null | undefined {
  if (text.trim() === '') return null
  return parseNumber(text, 2) ?? undefined
}

/** The cash of the entries that can be read. */
export function applied(entries: Entries): string {
  let total = ZERO
  for (const entry of Object.values(entries)) {
    const amount = readAmount(entry.amount)
    if (amount) total = add(total, amount)
  }
  return cents(total)
}

/** What the receipt leaves unapplied: negative when more is applied than came in. */
export function unapplied(amount: string, entries: Entries): string {
  return cents(subtract(readAmount(amount) ?? ZERO, stored(applied(entries))))
}

/**
 * The receipt spread over the suggestions in their order (the best matches first): each invoice up to what is open
 * of it, less the discount still on offer when the amount matched it so, until the money runs out.
 */
export function suggest(amount: string, suggestions: Suggestion[]): Entries {
  let left = readAmount(amount) ?? ZERO
  const entries: Entries = {}
  for (const suggestion of suggestions) {
    if (sign(left) <= 0) break
    const discount = suggestion.matched === 'AMOUNT_LESS_DISCOUNT' ? stored(suggestion.discountOffered) : ZERO
    const due = subtract(stored(suggestion.openAmount), discount)
    const cash = min(left, due)
    if (sign(cash) <= 0) continue
    entries[suggestion.invoiceId] = { amount: cents(cash), discount: sign(discount) > 0 ? cents(discount) : '' }
    left = subtract(left, cash)
  }
  return entries
}

/** The applications to send: the entries with an amount, in the order of the suggestions. */
export function toApplications(entries: Entries, order: string[]) {
  return order
    .filter((id) => readAmount(entries[id]?.amount ?? '') )
    .map((id) => ({
      invoiceId: id,
      amount: cents(readAmount(entries[id].amount) as Decimal),
      discount: readAmount(entries[id].discount) ? cents(readAmount(entries[id].discount) as Decimal) : null,
    }))
}

/** Whether any typed amount cannot be read. */
export function unreadable(entries: Entries): boolean {
  return Object.values(entries).some((e) => readAmount(e.amount) === undefined || readAmount(e.discount) === undefined)
}
