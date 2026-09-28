/** A date in the interface language, without time: stories record the day something happened (design section 3.4). */
export function formatDate(value: string | null | undefined, locale: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' }).format(date)
}

export function isoDate(value: string | null | undefined): string | undefined {
  return value ? value.slice(0, 10) : undefined
}
