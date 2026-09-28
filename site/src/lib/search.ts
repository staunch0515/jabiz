/** The template finds nothing outside these lengths (design section 7.2); the page says so rather than asking. */
export const MIN_WORDS = 2
export const MAX_WORDS = 100

export function searchable(q: string): boolean {
  const length = [...q].length
  return length >= MIN_WORDS && length <= MAX_WORDS
}
