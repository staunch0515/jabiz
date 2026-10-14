/**
 * Prefilling the profile (docs/quizbuks/plans/Q2-accounts.md, D-Q2-2 and Q2-9): the country from the browser's
 * language regions and time zone, without asking for the location and without any outside service. The taker sees it
 * and may change it; the server checks it against the country dictionary.
 */

/** Time zones whose country is clear, for browsers whose languages name no region. */
const ZONES: Record<string, string> = {
  'Asia/Tokyo': 'JP',
  'Asia/Shanghai': 'CN',
  'Asia/Chongqing': 'CN',
  'Asia/Urumqi': 'CN',
  'Asia/Hong_Kong': 'HK',
  'Asia/Taipei': 'TW',
  'Asia/Seoul': 'KR',
  'Asia/Singapore': 'SG',
  'Asia/Kolkata': 'IN',
  'Asia/Calcutta': 'IN',
  'Asia/Bangkok': 'TH',
  'Asia/Jakarta': 'ID',
  'Asia/Manila': 'PH',
  'Asia/Ho_Chi_Minh': 'VN',
  'Asia/Kuala_Lumpur': 'MY',
  'Europe/London': 'GB',
  'Europe/Dublin': 'IE',
  'Europe/Paris': 'FR',
  'Europe/Berlin': 'DE',
  'Europe/Madrid': 'ES',
  'Europe/Rome': 'IT',
  'Europe/Amsterdam': 'NL',
  'Europe/Brussels': 'BE',
  'Europe/Zurich': 'CH',
  'Europe/Vienna': 'AT',
  'Europe/Stockholm': 'SE',
  'Europe/Oslo': 'NO',
  'Europe/Copenhagen': 'DK',
  'Europe/Helsinki': 'FI',
  'Europe/Warsaw': 'PL',
  'Europe/Lisbon': 'PT',
  'America/New_York': 'US',
  'America/Chicago': 'US',
  'America/Denver': 'US',
  'America/Phoenix': 'US',
  'America/Los_Angeles': 'US',
  'America/Anchorage': 'US',
  'Pacific/Honolulu': 'US',
  'America/Toronto': 'CA',
  'America/Vancouver': 'CA',
  'America/Mexico_City': 'MX',
  'America/Sao_Paulo': 'BR',
  'America/Argentina/Buenos_Aires': 'AR',
  'Australia/Sydney': 'AU',
  'Australia/Melbourne': 'AU',
  'Pacific/Auckland': 'NZ',
}

/**
 * The likely country: the region of the first language that names one (`ja-JP` → JP), else the time zone's, else
 * none. Only codes in `known` (the dictionary) count.
 */
export function guessCountry(languages: readonly string[], timeZone: string | undefined,
  known: ReadonlySet<string>): string | null {
  for (const language of languages) {
    try {
      const region = new Intl.Locale(language).maximize().region
      // A language alone (`ja`) maximizes to its most likely region; only an explicit one is a hint of the place.
      if (region && /-[A-Za-z]{2}(?:-|$)/.test(language) && known.has(region.toUpperCase())) {
        return region.toUpperCase()
      }
    } catch {
      // not a language tag
    }
  }
  const zone = timeZone ? ZONES[timeZone] : undefined
  return zone && known.has(zone) ? zone : null
}

/** The browser's own hints. */
export function browserHints(): { languages: readonly string[]; timeZone: string | undefined } {
  let timeZone: string | undefined
  try {
    timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone
  } catch {
    timeZone = undefined
  }
  return { languages: navigator.languages ?? [navigator.language], timeZone }
}

const EURO = new Set(['AT', 'BE', 'CY', 'DE', 'EE', 'ES', 'FI', 'FR', 'GR', 'HR', 'IE', 'IT', 'LT', 'LU', 'LV',
  'MT', 'NL', 'PT', 'SI', 'SK'])

/** The reference currency a country most likely uses, among those offered; USD otherwise. */
export function currencyOf(country: string | null, offered: readonly string[]): string {
  const guess = country === 'JP' ? 'JPY'
    : country === 'CN' ? 'CNY'
      : country === 'IN' ? 'INR'
        : country === 'GB' ? 'GBP'
          : country && EURO.has(country) ? 'EUR'
            : 'USD'
  return offered.includes(guess) ? guess : offered[0] ?? guess
}
