import { compareDecimal, toDecimal } from './decimal'
import { controlOf, toWireValue } from './kinds'
import { parseInstant } from './validation'
import type { EntityMeta, FieldMeta } from './types'

/**
 * Entity metadata → the fields of the generated form (docs/design/12-frontend.md section 5). System-managed and
 * generated fields are the platform's; sensitive ones are written only by dedicated processes (400 SENSITIVE_FIELD
 * otherwise), so none of them is offered. Immutable fields can be set on creation and are read-only afterwards.
 * Process-only fields are shown read-only when editing and left out on creation, where the platform fills them
 * (docs/design/16-content-authoring.md section 5). Preset fields (a child created from its parent) are fixed. Masked
 * fields are read-only without their permission; their masked form is never sent, since only changed fields are.
 */
export type FormMode = 'create' | 'edit'

export interface FormField {
  field: FieldMeta
  disabled: boolean
  /** Read-only but sent: a preset value (the parent of a child created from its parent's details). */
  fixed?: boolean
}

export function formFieldsOf(
  entity: EntityMeta,
  mode: FormMode,
  preset: Record<string, unknown> = {},
  can: (permission: string) => boolean = () => false,
): FormField[] {
  return entity.fields
    .filter((f) => !f.systemManaged && !f.generated && !f.sensitive && !(f.processOnly && mode === 'create'))
    .map((field) => {
      const fixed = mode === 'create' && field.name in preset
      return {
        field,
        fixed,
        disabled:
          fixed ||
          field.processOnly ||
          // A masked field is written by holders of its permission only (docs/design/10-security.md section 13.1).
          (!!field.masked && !can(field.masked.permission)) ||
          (mode === 'edit' && (field.immutable || field.name === entity.primaryKey)),
      }
    })
}

/**
 * The values of the form as the server expects them. Fields never touched are absent; a field emptied in the form is
 * sent as null (an empty text box means "no value", not an empty text).
 */
export function wireAttributes(fields: FormField[], values: Record<string, unknown>): Record<string, unknown> {
  const attributes: Record<string, unknown> = {}
  for (const { field, disabled, fixed } of fields) {
    if (disabled && !fixed) continue
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
    case 'i18n':
      return JSON.stringify(sortedKeys(a)) === JSON.stringify(sortedKeys(b))
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

function sortedKeys(value: unknown): unknown {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return value
  return Object.fromEntries(Object.entries(value as Record<string, unknown>).sort(([x], [y]) => x.localeCompare(y)))
}
