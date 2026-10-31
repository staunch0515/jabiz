import { formatAmount, i18n, setAmountUnit } from '@jabiz/client'

/**
 * Amounts in QuizBuks (docs/quizbuks/02-design.md section 7.4): the ledger and the API keep yen without decimals, the
 * front ends show them as "1,234 Kudos" (学分 in Chinese, クドス in Japanese, Q61). This is the one place that says so;
 * every amount goes through {@link formatKudos}, never a currency name of its own.
 */
export const LEDGER_CURRENCY = 'JPY'
export const KUDOS_NAMES = { en: 'Kudos', zh: '学分', ja: 'クドス' } as const

/** What the points are called in a language (the interface's now by default); English for any other. */
export function kudosName(language: string = i18n.language ?? 'en'): string {
  const base = language.slice(0, 2)
  return base === 'zh' || base === 'ja' ? KUDOS_NAMES[base] : KUDOS_NAMES.en
}

/**
 * Makes the platform's amount formatting show the ledger currency as the points' name in the interface's language,
 * for components of `@jabiz/ui` that format amounts themselves, and again whenever the language changes (before the
 * pages render in it: this listener is registered when the module loads). Calling it again changes nothing.
 */
export function installKudos() {
  setAmountUnit(LEDGER_CURRENCY, kudosName())
  if (installed) return
  installed = true
  i18n.on('languageChanged', (language: string) => setAmountUnit(LEDGER_CURRENCY, kudosName(language)))
}

let installed = false

installKudos()

/** An amount of the ledger as people read it: `1,234 Kudos`, `1,234 学分`, `1,234 クドス`. */
export function formatKudos(value: unknown): string {
  return formatAmount(value, { currency: LEDGER_CURRENCY, scale: 0 })
}
