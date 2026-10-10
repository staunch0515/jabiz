import dayjs from 'dayjs'
import { describe, expect, it } from 'vitest'
import {
  checkInputs,
  compiledPattern,
  initialInputValues,
  InvalidJson,
  inputKindOf,
  inputNodes,
  toProcessInput,
  type JsonSchema,
} from './processForm'

/** The schema the server generates for LEDGER_POST-like input (runtime ProcessInputSchemas). */
const schema: JsonSchema = {
  type: 'object',
  properties: {
    bookingTime: { type: 'string', format: 'date-time' },
    description: { type: 'string', minLength: 1, pattern: '\\S' },
    password: { type: 'string', writeOnly: true, format: 'password' },
    entries: {
      type: 'array',
      minItems: 1,
      items: {
        type: 'object',
        properties: {
          accountCode: { type: 'string' },
          direction: { type: 'string', enum: ['DEBIT', 'CREDIT'] },
          amount: { type: ['string', 'number'], format: 'decimal' },
        },
        required: ['accountCode', 'direction', 'amount'],
      },
    },
    tags: { type: 'array', items: { type: 'string' } },
    count: { type: 'integer' },
    ratio: { type: 'number' },
    flag: { type: 'boolean' },
    day: { type: 'string', format: 'date' },
    mail: { type: 'string', format: 'email' },
    value: {},
    address: { type: 'object', properties: { city: { type: 'string' } } },
  },
  required: ['description', 'entries'],
}

describe('process form', () => {
  it('maps the input schema to inputs', () => {
    const nodes = inputNodes(schema)
    expect(nodes.map((n) => [n.name, n.kind, n.required])).toEqual([
      ['bookingTime', 'datetime', false],
      ['description', 'text', true],
      ['password', 'password', false],
      ['entries', 'list', true],
      ['tags', 'tags', false],
      ['count', 'integer', false],
      ['ratio', 'number', false],
      ['flag', 'boolean', false],
      ['day', 'date', false],
      ['mail', 'email', false],
      ['value', 'json', false],
      ['address', 'object', false],
    ])
    const entries = nodes.find((n) => n.name === 'entries')!
    expect(entries.children!.map((n) => [n.name, n.kind, n.required])).toEqual([
      ['accountCode', 'text', true],
      ['direction', 'enum', true],
      ['amount', 'decimal', true],
    ])
    expect(entries.children![1].options).toEqual(['DEBIT', 'CREDIT'])
    expect(inputKindOf({ type: 'array', items: { type: 'integer' } })).toBe('json')
  })

  it('turns the form values into the request body', () => {
    const nodes = inputNodes(schema)
    const body = toProcessInput(nodes, {
      bookingTime: dayjs('2026-01-31T15:00:00Z'),
      description: 'Rent',
      entries: [
        { accountCode: '5100', direction: 'DEBIT', amount: '1000.50' },
        { accountCode: '1110', direction: 'CREDIT', amount: 1000.5 },
      ],
      count: 3,
      day: dayjs('2026-02-01T00:00:00'),
      value: '{"x": [1, 2]}',
      address: { city: 'Tokyo' },
      mail: '',
    })
    expect(body).toEqual({
      bookingTime: '2026-01-31T15:00:00.000Z',
      description: 'Rent',
      entries: [
        { accountCode: '5100', direction: 'DEBIT', amount: '1000.50' },
        { accountCode: '1110', direction: 'CREDIT', amount: '1000.5' },
      ],
      count: 3,
      day: '2026-02-01',
      value: { x: [1, 2] },
      address: { city: 'Tokyo' },
    })
  })

  it('reports unreadable JSON with its path', () => {
    expect(() => toProcessInput(inputNodes(schema), { value: '{oops' })).toThrow(InvalidJson)
    try {
      toProcessInput(inputNodes(schema), { value: '{oops' })
    } catch (e) {
      expect((e as InvalidJson).path).toBe('value')
    }
  })

  it('turns the text of number inputs into JSON numbers, and leaves out empty tags', () => {
    const nodes = inputNodes(schema)
    expect(toProcessInput(nodes, { count: '42', ratio: ' 0.5 ', tags: [], day: '2026-02-01', bookingTime: '2026-01-31T15:00:00.000Z' })).toEqual({
      count: 42,
      ratio: 0.5,
      day: '2026-02-01',
      bookingTime: '2026-01-31T15:00:00.000Z',
    })
    // Text that is no number goes as it is, for the server to refuse.
    expect(toProcessInput(nodes, { count: '4x' })).toEqual({ count: '4x' })
    expect(toProcessInput(nodes, { count: '', flag: false })).toEqual({ flag: false })
  })

  it('checks only required inputs and patterns, in objects and list items', () => {
    const nodes = inputNodes(schema)
    const values = initialInputValues(nodes)
    expect(values).toMatchObject({ description: '', tags: [], flag: undefined, address: { city: '' } })
    expect(values.entries).toEqual([{ accountCode: '', direction: '', amount: '' }])
    expect(checkInputs(nodes, values).map((p) => [p.path, p.message, p.ruleCode])).toEqual([
      ['description', 'description: REQUIRED', 'REQUIRED'],
      ['entries.0.accountCode', 'accountCode: REQUIRED', 'REQUIRED'],
      ['entries.0.direction', 'direction: REQUIRED', 'REQUIRED'],
      ['entries.0.amount', 'amount: REQUIRED', 'REQUIRED'],
    ])
    expect(
      checkInputs(nodes, {
        description: ' ',
        entries: [{ accountCode: '1', direction: 'DEBIT', amount: '1' }],
      }).map((p) => p.message),
    ).toEqual(['description: INVALID_VALUE'])
    // No items: nothing to check in them (the server's minItems decides).
    expect(checkInputs(nodes, { description: 'x', entries: [] })).toEqual([])
    // A pattern this browser cannot compile is left to the server.
    expect(checkInputs(inputNodes({ type: 'object', properties: { a: { type: 'string', pattern: '(?<' } } }), { a: 'x' })).toEqual([])
  })

  it('refuses fields holding typed text that is no value, whatever their value', () => {
    const nodes = inputNodes(schema)
    const values = { description: 'x', day: '', entries: [{ accountCode: '1', direction: 'DEBIT', amount: '1' }] }
    expect(checkInputs(nodes, values, '', new Set(['day', 'entries.0.amount']))).toEqual([
      { path: 'entries.0.amount', message: 'amount: INVALID_VALUE', ruleCode: 'INVALID_VALUE', shownByField: true },
      { path: 'day', message: 'day: INVALID_VALUE', ruleCode: 'INVALID_VALUE', shownByField: true },
    ])
    expect(compiledPattern('\\d+')?.test('42')).toBe(true)
    expect(compiledPattern('(?<')).toBeUndefined()
    expect(compiledPattern(undefined)).toBeUndefined()
  })

  it('starts with the row key filled in, a required boolean off and required tags empty', () => {
    const nodes = inputNodes({
      type: 'object',
      properties: { id: { type: 'string' }, on: { type: 'boolean' }, tags: { type: 'array', items: { type: 'string' } } },
      required: ['on', 'tags'],
    })
    expect(initialInputValues(nodes, { id: 'p-1' })).toEqual({ id: 'p-1', on: false, tags: [] })
    expect(checkInputs(nodes, { id: 'p-1', on: false, tags: [] }).map((p) => p.message)).toEqual(['tags: REQUIRED'])
  })
})
