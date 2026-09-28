/**
 * The flag of an ISO 3166-1 country code as regional indicator symbols (design section 3.1): shown at text size
 * next to a place's name, never as an image. Places that are not countries have no code and no flag.
 */
export function flag(countryCode: string | null | undefined): string {
  if (!countryCode || !/^[A-Z]{2}$/.test(countryCode)) return ''
  return String.fromCodePoint(...[...countryCode].map((c) => 0x1f1e6 + c.charCodeAt(0) - 65))
}
