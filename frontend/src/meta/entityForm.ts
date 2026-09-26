import { compareDecimal, toDecimal } from './decimal'
import { controlOf, toWireValue } from './kinds'
import { parseInstant } from './validation'
import type { EntityMeta, FieldMeta } from './types'

/**
 * Entity metadata → the fields of the generated form (docs/design/12-frontend.md section 5). System-managed and
 * generated fields are the platform's; sensitive ones are written only by dedicated processes (400 SENSITIVE_FIELD
 * otherwise), so none of them is offered. Immutable fields can be set on creation and are read-only afterwards.
 */
export type FormMode = 'create' | 'edit'

export interface FormField {
  field: FieldMeta
  disabled: boolean
}

export function formFieldsOf(entity: EntityMeta, mode: FormMode): FormField[] {
  return entity.fields
    .filter((f) => !f.systemManaged && !f.generated && !f.sensitive)
    .map((field) => ({ field, disabled: mode === 'edit' && (field.immutable || field.name === entity.primaryKey) }))
}

/**
 * The values of the form as the server expects them. Fields never touched are absent; a field emptied in the form is
 * sent as null (an empty text box means "no value", not an empty text).
 */
export function wireAttributes(fields: FormField[], values: Record<string, unknown>): Record<string, unknown> {
  const attributes: Record<string, unknown> = {}
  for (const { field, disabled } of fields) {
    if (disabled) continue
    const value = toWireValue(field, values[field.name])
    if (value === undefined) continue
    attributes[field.name] = value === '' ? null : value
  }
  return attributes
}

/** Whether two values of the field mean the same (decimals by value, times by instant). */
export function sameValue(field: FieldMeta, a: unknown, b: unknown): boolean {
  const empty = (v: unknown) => v === null || v === undefined
  if (empty(a) || empty(b)) return empty(a) && empty(b)
  switch (controlOf(field)) {
    case 'decimal': {
      const x = toDecimal(a)
      const y = toDecimal(b)
      return x && y ? compareDecimal(x, y) === 0 : String(a) === String(b)
    }
    case 'datetime': {
      // The date picker holds milliseconds; a stored time with finer digits is unchanged when equal to the millisecond.
      const x = parseInstant(String(a))
      const y = parseInstant(String(b))
      return x !== null && y !== null ? x / 1_000_000n === y / 1_000_000n : String(a) === String(b)
    }
    case 'json':
      return JSON.stringify(a) === JSON.stringify(b)
    default:
      return String(a) === String(b)
  }
}

/**
 * The attributes of an update: only what changed. The server validates what is sent and keeps the rest; sending an
 * unchanged immutable value would be harmless, but sending less keeps concurrent edits of other fields apart.
 * A field cleared in the form is sent as null.
 */
export function changedAttributes(
  fields: FormField[],
  original: Record<string, unknown>,
  values: Record<string, unknown>,
): Record<string, unknown> {
  const wire = wireAttributes(fields, values)
  const changed: Record<string, unknown> = {}
  for (const { field, disabled } of fields) {
    if (disabled) continue
    const next = field.name in wire ? wire[field.name] : null
    if (!sameValue(field, original[field.name], next)) changed[field.name] = next
  }
  return changed
}
