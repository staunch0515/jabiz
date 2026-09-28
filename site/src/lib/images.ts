import { fileUrl } from '../api/client'

/** The width variants of the culture.image policy the site asks for (docs/culture/00-design.md section 3.11). */
export const VARIANTS = [320, 640, 1280] as const

export function srcSet(fileId: string): string {
  return VARIANTS.map((w) => `${fileUrl(fileId, `w${w}`)} ${w}w`).join(', ')
}
