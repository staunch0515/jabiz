import dayjs from 'dayjs'
import { describe, expect, it } from 'vitest'
import { InvalidJson, inputKindOf, inputNodes, toProcessInput, type JsonSchema } from './processForm'

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
})
