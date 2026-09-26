import { describe, expect, it } from 'vitest'
import type { EntityMeta, FieldMeta } from './types'
import { describe as describeViolation, formatMessage, parseInstant, validateAttributes, validateField } from './validation'

const base = { immutable: false, required: false, generated: false, systemManaged: false, sensitive: false, operators: [], rules: [] }

describe('validation details', () => {
  it('parses ISO-8601 instants with offsets only', () => {
    expect(parseInstant('1970-01-01T00:00:01Z')).toBe(1_000_000_000n)
    expect(parseInstant('1970-01-01T09:00:00+09:00')).toBe(0n)
    expect(parseInstant('1970-01-01t00:00z')).toBe(0n)
    expect(parseInstant('1970-01-01T00:00:00.5Z')).toBe(500_000_000n)
    for (const bad of ['1970-01-01', '1970-01-01T00:00:00', '1970-02-30T00:00:00Z', '1970-01-01T24:00:00Z', 'x']) {
      expect(parseInstant(bad)).toBeNull()
    }
  })

  it('skips system-managed fields and custom kinds', () => {
    const system = { ...base, name: 'createdTime', type: 'temporal', role: 'SYSTEM_RECORDED', systemManaged: true } as FieldMeta
    expect(validateField(system, 'nonsense', { insert: true })).toEqual([])
    const custom = {
      ...base,
      name: 'weight',
      type: 'custom',
      kindId: 'geo.quantity',
      required: true,
      rules: [{ code: 'VALID_MASS_RANGE', kind: 'RANGE', params: { min: 1, max: 10 } }],
    } as FieldMeta
    // The server converts custom kinds through its SPI (here to a decimal); the client leaves their rules to it.
    expect(validateField(custom, '5', { insert: true })).toEqual([])
    expect(validateField(custom, 500, { insert: true })).toEqual([])
    expect(validateField(custom, { any: 'thing' }, { insert: true })).toEqual([])
    expect(validateField(custom, undefined, { insert: true }).map((v) => v.ruleCode)).toEqual(['REQUIRED'])
  })

  it('leaves a pattern this browser cannot compile to the server', () => {
    const text = {
      ...base,
      name: 'code',
      type: 'text',
      multiline: false,
      rules: [{ code: 'BROKEN', kind: 'PATTERN', params: { regex: '(' } }],
    } as FieldMeta
    expect(validateField(text, 'x', { insert: false })).toEqual([])
  })

  it('checks versions as 64-bit integers', () => {
    const version = { ...base, name: 'v', type: 'version' } as FieldMeta
    expect(validateField(version, '0x1F', { insert: false })).toEqual([])
    expect(validateField(version, 1.5, { insert: false }).map((v) => v.ruleCode)).toEqual(['INVALID_VALUE'])
  })

  it('validates the offered fields of an entity and describes violations with the server templates', () => {
    const entity = {
      entity: 'Sample',
      label: 'Sample',
      primaryKey: 'id',
      temporal: false,
      publishesChanges: false,
      fields: [
        { ...base, name: 'id', type: 'semanticIdentity', urn: 'u', generated: true, required: true },
        { ...base, name: 'title', type: 'text', maxLength: 3, multiline: false, required: true },
        { ...base, name: 'secret', type: 'text', multiline: false, sensitive: true, required: true },
      ],
      references: [],
      listViews: [],
      dictionaries: [],
      unique: [],
      messages: { TOO_LONG: 'Field "{field}" allows at most {max} characters.' },
    } as EntityMeta
    const found = validateAttributes(entity, { title: 'abcd' }, { insert: true })
    expect(found).toEqual([{ field: 'title', ruleCode: 'TOO_LONG', params: { max: 3 } }])
    expect(describeViolation(entity, found[0], 'Title')).toEqual({
      field: 'title',
      ruleCode: 'TOO_LONG',
      params: { max: 3 },
      message: 'Field "Title" allows at most 3 characters.',
    })
    expect(describeViolation(entity, { field: 'title', ruleCode: 'NO_TEXT', params: {} }).message).toBe('NO_TEXT')
  })

  it('fills named placeholders and keeps unknown ones', () => {
    expect(formatMessage('{a} and {b}', { a: 1 })).toBe('1 and {b}')
  })
})
