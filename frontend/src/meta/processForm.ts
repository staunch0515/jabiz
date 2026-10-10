import dayjs from 'dayjs'

/**
 * Process input JSON Schema (GET /api/meta/processes, runtime ProcessInputSchemas) → a tree of form inputs, and the
 * form's values → the request body of POST /api/processes/{name}/{version} (docs/design/12-frontend.md section 6).
 */
export interface JsonSchema {
  type?: string | string[]
  format?: string
  title?: string
  enum?: string[]
  properties?: Record<string, JsonSchema>
  required?: string[]
  items?: JsonSchema
  writeOnly?: boolean
  pattern?: string
  minLength?: number
  maxLength?: number
  minItems?: number
  maxItems?: number
  minimum?: number
  maximum?: number
  exclusiveMinimum?: number
}

export type InputKind =
  | 'text'
  | 'password'
  | 'email'
  | 'datetime'
  | 'date'
  | 'decimal'
  | 'integer'
  | 'number'
  | 'boolean'
  | 'enum'
  | 'object'
  | 'list'
  | 'tags'
  | 'json'

export interface InputNode {
  name: string
  kind: InputKind
  required: boolean
  schema: JsonSchema
  /** Enum values. */
  options?: string[]
  /** Properties of an object, or of the items of a list of objects. */
  children?: InputNode[]
}

function types(schema: JsonSchema): string[] {
  return schema.type === undefined ? [] : Array.isArray(schema.type) ? schema.type : [schema.type]
}

export function inputKindOf(schema: JsonSchema): InputKind {
  const t = types(schema)
  if (schema.format === 'decimal') return 'decimal'
  if (schema.enum) return 'enum'
  if (t.includes('string')) {
    if (schema.format === 'password' || schema.writeOnly) return 'password'
    if (schema.format === 'date-time') return 'datetime'
    if (schema.format === 'date') return 'date'
    if (schema.format === 'email') return 'email'
    return 'text'
  }
  if (t.includes('integer')) return 'integer'
  if (t.includes('number')) return 'number'
  if (t.includes('boolean')) return 'boolean'
  if (t.includes('array')) {
    const item = schema.items ?? {}
    if (types(item).includes('object') && item.properties) return 'list'
    const itemKind = inputKindOf(item)
    return itemKind === 'text' || itemKind === 'enum' ? 'tags' : 'json'
  }
  if (t.includes('object') && schema.properties) return 'object'
  return 'json'
}

/** The inputs of an object schema, in declaration order. */
export function inputNodes(schema: JsonSchema): InputNode[] {
  const required = new Set(schema.required ?? [])
  return Object.entries(schema.properties ?? {}).map(([name, property]) => {
    const kind = inputKindOf(property)
    const node: InputNode = { name, kind, required: required.has(name), schema: property }
    if (kind === 'enum') node.options = property.enum
    if (kind === 'object') node.children = inputNodes(property)
    if (kind === 'list') node.children = inputNodes(property.items ?? {})
    return node
  })
}

function blank(value: unknown): boolean {
  return value === undefined || value === null || value === ''
}

class InvalidJson extends Error {
  readonly path: string
  constructor(path: string) {
    super(`invalid JSON at ${path}`)
    this.path = path
  }
}

function convert(node: InputNode, value: unknown, path: string): unknown {
  if (blank(value)) return undefined
  switch (node.kind) {
    // The new form (SchemaForm) holds instants and dates as their API text; the old one (SchemaInputs, until
    // phase 15c) as Dayjs.
    case 'datetime':
      return dayjs.isDayjs(value) ? value.toISOString() : value
    case 'date':
      return dayjs.isDayjs(value) ? value.format('YYYY-MM-DD') : value
    case 'decimal':
      // Exact text: the server reads it into a BigDecimal without passing through binary floating point.
      return String(value)
    case 'integer':
    case 'number': {
      // Number fields hold their text; the API takes a JSON number. Text that is no number goes as it is, for the
      // server to refuse.
      if (typeof value !== 'string') return value
      const number = Number(value.trim())
      return value.trim() !== '' && Number.isFinite(number) ? number : value
    }
    case 'tags':
      return Array.isArray(value) && value.length === 0 ? undefined : value
    case 'object':
      return toProcessInput(node.children ?? [], value as Record<string, unknown>, `${path}.`)
    case 'list':
      return (value as Record<string, unknown>[]).map((item, i) =>
        toProcessInput(node.children ?? [], item ?? {}, `${path}[${i}].`),
      )
    case 'json':
      if (typeof value !== 'string') return value
      try {
        return JSON.parse(value)
      } catch {
        throw new InvalidJson(path)
      }
    default:
      return value
  }
}

/** The request body for the form's values; empty inputs are left out. Throws InvalidJson for unreadable JSON. */
export function toProcessInput(nodes: InputNode[], values: Record<string, unknown>, prefix = ''): Record<string, unknown> {
  const body: Record<string, unknown> = {}
  for (const node of nodes) {
    const converted = convert(node, values?.[node.name], prefix + node.name)
    if (converted !== undefined) body[node.name] = converted
  }
  return body
}

export { InvalidJson }

/** A field the form refuses to send: its path in the form's values (`entries.0.amount`) and the message. */
export interface InputProblem {
  path: string
  /** `<name>: REQUIRED` or `<name>: INVALID_VALUE`, as the server's rule codes. */
  message: string
  ruleCode: 'REQUIRED' | 'INVALID_VALUE'
  /** The field shows the problem itself (a date field holding text that is no date). */
  shownByField?: boolean
}

/**
 * The schema's pattern, when this browser can compile it; otherwise the server checks it alone. Shared by the
 * process form (`checkInputs`) and the old form inputs of the report and import pages (`SchemaInputs`, until 15c).
 */
export function compiledPattern(pattern: string | undefined): RegExp | undefined {
  if (!pattern) return undefined
  try {
    return new RegExp(pattern, 'u')
  } catch {
    return undefined
  }
}

function missing(node: InputNode, value: unknown): boolean {
  if (node.kind === 'tags') return !Array.isArray(value) || value.length === 0
  return blank(value)
}

/**
 * What the process form checks before sending (docs/design/12-frontend.md section 6): only that required inputs are
 * filled in and that text matches the schema's `pattern`; everything else is the server's to judge, and its answer
 * is shown as it comes. Objects and the items of lists are checked field by field; a required boolean is always
 * filled in (false until switched on). `typedInvalid` names the fields (by path) holding typed text the field could
 * not take (a date that is no date): they are INVALID_VALUE whatever their value, so the form is not sent without
 * them.
 */
export function checkInputs(
  nodes: InputNode[],
  values: Record<string, unknown> | undefined,
  prefix = '',
  typedInvalid: ReadonlySet<string> = new Set(),
): InputProblem[] {
  const problems: InputProblem[] = []
  for (const node of nodes) {
    const path = prefix + node.name
    const value = values?.[node.name]
    if (node.kind === 'object') {
      problems.push(...checkInputs(node.children ?? [], value as Record<string, unknown> | undefined, `${path}.`, typedInvalid))
      continue
    }
    if (node.kind === 'list') {
      const items = Array.isArray(value) ? (value as Record<string, unknown>[]) : []
      items.forEach((item, i) => problems.push(...checkInputs(node.children ?? [], item, `${path}.${i}.`, typedInvalid)))
      continue
    }
    if (typedInvalid.has(path)) {
      problems.push({ path, message: `${node.name}: INVALID_VALUE`, ruleCode: 'INVALID_VALUE', shownByField: true })
      continue
    }
    if (missing(node, value)) {
      if (node.required) problems.push({ path, message: `${node.name}: REQUIRED`, ruleCode: 'REQUIRED' })
      continue
    }
    const pattern = compiledPattern(node.schema.pattern)
    if (pattern && typeof value === 'string' && !pattern.test(value)) {
      problems.push({ path, message: `${node.name}: INVALID_VALUE`, ruleCode: 'INVALID_VALUE' })
    }
  }
  return problems
}

/**
 * The values a new process form starts with: empty text, no tags, a required boolean switched off, one empty item
 * for a required list; and the inputs filled in by the row an action was started on (`preset`, top level only).
 */
export function initialInputValues(nodes: InputNode[], preset: Record<string, string> = {}): Record<string, unknown> {
  const values: Record<string, unknown> = {}
  for (const node of nodes) {
    switch (node.kind) {
      case 'boolean':
        values[node.name] = node.required ? false : undefined
        break
      case 'tags':
        values[node.name] = []
        break
      case 'object':
        values[node.name] = initialInputValues(node.children ?? [])
        break
      case 'list':
        values[node.name] = node.required ? [initialInputValues(node.children ?? [])] : []
        break
      default:
        values[node.name] = ''
    }
    if (preset[node.name] !== undefined) values[node.name] = preset[node.name]
  }
  return values
}
