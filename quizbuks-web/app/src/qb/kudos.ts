import { formatAmount, setAmountUnit } from '@jabiz/client'

/**
 * Amounts in QuizBuks (docs/quizbuks/02-design.md section 7.4): the ledger and the API keep yen without decimals, the
 * front ends show them as "1,234 Kudos". This is the one place that says so; every amount goes through
 * {@link formatKudos}, never a currency name of its own.
 */
export const LEDGER_CURRENCY = 'JPY'
export const KUDOS = 'Kudos'

/**
 * Makes the platform's amount formatting show the ledger currency as Kudos, for components of `@jabiz/ui` that format
 * amounts themselves. Done when this module is loaded; calling it again changes nothing.
 */
export function installKudos() {
  setAmountUnit(LEDGER_CURRENCY, KUDOS)
}

installKudos()

/** An amount of the ledger as people read it: `1,234 Kudos`. */
export function formatKudos(value: unknown): string {
  return formatAmount(value, { currency: LEDGER_CURRENCY, scale: 0 })
}
