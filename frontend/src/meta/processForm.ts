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
    case 'datetime':
      return dayjs.isDayjs(value) ? value.toISOString() : value
    case 'date':
      return dayjs.isDayjs(value) ? value.format('YYYY-MM-DD') : value
    case 'decimal':
      // Exact text: the server reads it into a BigDecimal without passing through binary floating point.
      return String(value)
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
