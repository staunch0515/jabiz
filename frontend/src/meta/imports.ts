import type { ImportEntry } from '../api/imports'

/** The label of an import field, falling back to its name (docs/design/20-imports.md section 6). */
export function fieldLabel(entry: ImportEntry | undefined, field: string | undefined): string {
  if (!field) return ''
  return entry?.fields?.find((f) => f.name === field)?.label ?? field
}
