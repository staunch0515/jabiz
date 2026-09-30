import {
  compareDecimal,
  decimalFromNumber,
  parseDecimal,
  precision,
  signum,
  stripTrailingZeros,
  toDecimal,
  type Decimal,
} from './decimal'
import { i18nParams, InvalidTexts, isI18nText, normalizeTexts, textsViolation } from './i18nText'
import type { EntityMeta, FieldMeta, RuleSpec, Violation } from './types'

/**
 * Client-side validation that reports exactly the rule codes the server reports for the same input (decision D15).
 * It mirrors the server's single validation path (core EntityValidator and FieldValueCoercer) step by step:
 *
 * 1. convert the value to the canonical type of the field's semantic kind, else INVALID_VALUE;
 * 2. a missing value of a required field: REQUIRED (on insert, or when explicitly cleared);
 * 3. the constraint of the kind: TOO_LONG, NUMERIC_PRECISION, MONETARY_SCALE, NOT_IN_DICTIONARY, and for multilingual texts TOO_LONG
 *    (with lang) or TRANSLATION_REQUIRED (the first failing one only; decision D20);
 * 4. every exported rule (RANGE, SCALE, LENGTH, PATTERN, NOT_FUTURE, REQUIRED), in declaration order.
 *
 * The shared cases in spec/validation-cases.json run through both implementations. Server-only rules and the other
 * custom kinds (except the form of a jabiz.file id) are not checked here; the server remains the authority and its
 * answer is shown the same way.
 */

/** A value converted to the canonical type of its kind. */
type Canonical =
  | { k: 'decimal'; d: Decimal }
  | { k: 'text'; s: string }
  | { k: 'instant'; nanos: bigint }
  | { k: 'date'; s: string }
  | { k: 'bool'; b: boolean }
  | { k: 'long'; n: bigint }
  | { k: 'texts'; t: Record<string, string> }
  | { k: 'raw'; v: unknown }

export interface ValidateOptions {
  /** Whether the entity is new: required fields must then be present. */
  insert: boolean
  /** The client's current time, for NOT_FUTURE; only a hint, the server's clock decides. */
  now?: Date
  /** Enabled codes of dictionaries, by URN, for code fields without fixed values. */
  dictionaries?: Record<string, ReadonlySet<string> | readonly string[] | undefined>
}

export interface FieldViolation {
  field: string
  ruleCode: string
  params: Record<string, unknown>
}

class InvalidValue extends Error {}

const FILE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const INSTANT =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2}(?::\d{2})?)$/i

function daysInMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate()
}

/** ISO-8601 with an offset (Instant.parse, then OffsetDateTime.parse on the server) to epoch nanoseconds. */
export function parseInstant(text: string): bigint | null {
  const m = INSTANT.exec(text.trim())
  if (!m) return null
  const [, y, mo, d, h, mi, s = '0', fraction = '', offset] = m
  const year = Number(y)
  const month = Number(mo)
  const day = Number(d)
  const hour = Number(h)
  const minute = Number(mi)
  const second = Number(s)
  if (month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) return null
  if (hour > 23 || minute > 59 || second > 59) return null
  let offsetSeconds = 0
  if (offset.toUpperCase() !== 'Z') {
    const sign = offset.startsWith('-') ? -1 : 1
    const [oh, om, os = '0'] = offset.slice(1).split(':')
    if (Number(oh) > 18 || Number(om) > 59 || Number(os) > 59) return null
    offsetSeconds = sign * (Number(oh) * 3600 + Number(om) * 60 + Number(os))
  }
  const epochSeconds = BigInt(Date.UTC(year, month - 1, day, hour, minute, second) / 1000) - BigInt(offsetSeconds)
  const nanos = BigInt((fraction + '000000000').slice(0, 9))
  return epochSeconds * 1_000_000_000n + nanos
}

function toBooleanValue(raw: unknown): boolean {
  if (typeof raw === 'boolean') return raw
  if (typeof raw === 'string') {
    const text = raw.trim().toLowerCase()
    if (text === 'true') return true
    if (text === 'false') return false
  }
  throw new InvalidValue()
}

function toDecimalValue(raw: unknown): Decimal {
  const d = typeof raw === 'number' ? decimalFromNumber(raw) : typeof raw === 'string' ? parseDecimal(raw.trim()) : null
  if (!d) throw new InvalidValue()
  return d
}

function isIsoDate(text: string): boolean {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text)
  if (!m) return false
  const [year, month, day] = [Number(m[1]), Number(m[2]), Number(m[3])]
  const date = new Date(Date.UTC(year, month - 1, day))
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
}

function toLongValue(raw: unknown): bigint {
  if (typeof raw === 'number' && Number.isInteger(raw)) return BigInt(raw)
  if (typeof raw === 'string') {
    const text = raw.trim()
    if (/^0x[0-9a-f]+$/i.test(text)) return BigInt(text)
    if (/^[+-]?\d+$/.test(text)) return BigInt(text)
  }
  throw new InvalidValue()
}

/** FieldValueCoercer.coerce(field, raw, enforceDictionary = true); null stays null. */
function coerce(field: FieldMeta, raw: unknown): Canonical | null {
  if (raw === null || raw === undefined) return null
  switch (field.type) {
    case 'temporal': {
      if (typeof raw !== 'string') throw new InvalidValue()
      const nanos = parseInstant(raw)
      if (nanos === null) throw new InvalidValue()
      return { k: 'instant', nanos }
    }
    case 'date': {
      // LocalDate.parse: exactly YYYY-MM-DD and a day that exists.
      if (typeof raw !== 'string' || !isIsoDate(raw.trim())) throw new InvalidValue()
      return { k: 'date', s: raw.trim() }
    }
    case 'monetary':
    case 'numeric':
      return { k: 'decimal', d: toDecimalValue(raw) }
    case 'version':
      return { k: 'long', n: toLongValue(raw) }
    case 'code': {
      const value = String(raw)
      if (field.allowedValues.length > 0 && !field.allowedValues.includes(value)) throw new InvalidValue()
      return { k: 'text', s: value }
    }
    case 'text':
      if (typeof raw !== 'string') throw new InvalidValue()
      return { k: 'text', s: raw }
    case 'bool':
      return { k: 'bool', b: toBooleanValue(raw) }
    case 'custom': {
      // jabiz.file (FileKindSupport): the canonical text form of a UUID, either case.
      if (field.kindId === 'jabiz.file') {
        if (typeof raw !== 'string' || !FILE_ID.test(raw)) throw new InvalidValue()
        return { k: 'text', s: raw.toLowerCase() }
      }
      if (!isI18nText(field)) return { k: 'raw', v: raw }
      try {
        const texts = normalizeTexts(raw, i18nParams(field).locales)
        return texts === null ? null : { k: 'texts', t: texts }
      } catch (e) {
        if (e instanceof InvalidTexts) throw new InvalidValue()
        throw e
      }
    }
    default:
      return { k: 'raw', v: raw }
  }
}

function codePoints(s: string): number {
  return [...s].length
}

/** BigDecimal fits numeric(precision, scale), as EntityValidator.fits. */
function fits(value: Decimal, p: number, s: number): boolean {
  const stripped = stripTrailingZeros(value)
  const scale = Math.max(stripped.scale, 0)
  const integerDigits = signum(stripped) === 0 ? 0 : Math.max(precision(stripped) - stripped.scale, 0)
  return scale <= s && integerDigits <= p - s
}

function kindViolation(field: FieldMeta, value: Canonical, options: ValidateOptions): FieldViolation | null {
  if (field.type === 'text' && field.maxLength != null && value.k === 'text' && codePoints(value.s) > field.maxLength) {
    return { field: field.name, ruleCode: 'TOO_LONG', params: { max: field.maxLength } }
  }
  if (field.type === 'numeric' && value.k === 'decimal' && !fits(value.d, field.precision, field.scale)) {
    return {
      field: field.name,
      ruleCode: 'NUMERIC_PRECISION',
      params: { precision: field.precision, scale: field.scale },
    }
  }
  // The currency's scale, unless the field has its own SCALE rule (which reports under its code), as EntityValidator.
  if (
    field.type === 'monetary' &&
    value.k === 'decimal' &&
    !field.rules.some((rule) => rule.kind === 'SCALE') &&
    Math.max(stripTrailingZeros(value.d).scale, 0) > field.scale
  ) {
    return { field: field.name, ruleCode: 'MONETARY_SCALE', params: { scale: field.scale, currency: field.currency } }
  }
  if (field.type === 'code' && field.allowedValues.length === 0 && value.k === 'text') {
    const codes = options.dictionaries?.[field.dictUrn]
    if (codes) {
      const known = codes instanceof Set ? codes.has(value.s) : (codes as readonly string[]).includes(value.s)
      if (!known) {
        return { field: field.name, ruleCode: 'NOT_IN_DICTIONARY', params: { value: value.s, dict: field.dictUrn } }
      }
    }
  }
  return null
}

/** Whether the canonical value satisfies the rule; mirrors the predicates of core Rules. */
function satisfies(rule: RuleSpec, value: Canonical, options: ValidateOptions): boolean {
  const p = rule.params ?? {}
  switch (rule.kind) {
    case 'RANGE': {
      if (value.k !== 'decimal') return false
      const min = p.min === undefined ? null : toDecimal(p.min)
      const max = p.max === undefined ? null : toDecimal(p.max)
      return (!min || compareDecimal(value.d, min) >= 0) && (!max || compareDecimal(value.d, max) <= 0)
    }
    case 'SCALE':
      return value.k === 'decimal' && stripTrailingZeros(value.d).scale <= Number(p.scale)
    case 'LENGTH': {
      if (value.k !== 'text') return false
      const n = codePoints(value.s)
      return (p.min === undefined || n >= Number(p.min)) && (p.max === undefined || n <= Number(p.max))
    }
    case 'PATTERN': {
      if (value.k !== 'text') return false
      try {
        return new RegExp(`^(?:${String(p.regex)})$`, 'u').test(value.s)
      } catch {
        // The server only exports portable patterns; should one still not compile here, the server decides.
        return true
      }
    }
    case 'NOT_FUTURE': {
      if (value.k !== 'instant') return false
      const now = BigInt((options.now ?? new Date()).getTime()) * 1_000_000n
      return value.nanos <= now + BigInt(Number(p.toleranceSeconds)) * 1_000_000_000n
    }
    case 'REQUIRED':
      return value.k !== 'text' || value.s.trim().length > 0
    default:
      // Kinds the server refuses to export; nothing to check.
      return true
  }
}

/**
 * The violations of one field, in the order the server reports them. {@code present} tells a value left out
 * (undefined) from one explicitly sent as null.
 */
export function validateField(field: FieldMeta, raw: unknown, options: ValidateOptions): FieldViolation[] {
  if (field.systemManaged) return []
  const present = raw !== undefined
  let value: Canonical | null
  try {
    value = coerce(field, raw)
  } catch (e) {
    if (e instanceof InvalidValue) return [{ field: field.name, ruleCode: 'INVALID_VALUE', params: {} }]
    throw e
  }
  if (value === null) {
    return field.required && (options.insert || present)
      ? [{ field: field.name, ruleCode: 'REQUIRED', params: {} }]
      : []
  }
  if (value.k === 'texts') {
    const violation = textsViolation(i18nParams(field), value.t)
    return violation ? [{ field: field.name, ruleCode: violation.ruleCode, params: violation.params }] : []
  }
  // A custom kind's canonical type is decided by its server-side SPI; its rules are left to the server.
  if (field.type === 'custom') return []
  const kind = kindViolation(field, value, options)
  if (kind) return [kind]
  return field.rules
    .filter((rule) => !satisfies(rule, value, options))
    .map((rule) => ({ field: field.name, ruleCode: rule.code, params: rule.params ?? {} }))
}

/**
 * The violations of a set of attributes of one entity: every field that is sent, plus the required ones on insert.
 * Fields the form does not offer (generated, system managed) are skipped.
 */
export function validateAttributes(
  entity: EntityMeta,
  attributes: Record<string, unknown>,
  options: ValidateOptions,
): FieldViolation[] {
  return entity.fields
    .filter((f) => !f.generated && !f.systemManaged && !f.sensitive && !f.processOnly)
    .flatMap((f) => validateField(f, attributes[f.name], options))
}

/** Fills named placeholders such as {min}, as the server's MessageTemplate does; unknown ones stay visible. */
export function formatMessage(template: string, params: Record<string, unknown>): string {
  return template.replace(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g, (whole, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : whole,
  )
}

/** The violation with its message from the entity's templates ({field} is the field's label). */
export function describe(entity: EntityMeta, v: FieldViolation, fieldLabel?: string): Violation {
  const template = entity.messages?.[v.ruleCode] ?? v.ruleCode
  return {
    field: v.field,
    ruleCode: v.ruleCode,
    params: v.params,
    message: formatMessage(template, { ...v.params, field: fieldLabel ?? v.field }),
  }
}
