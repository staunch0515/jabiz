import { describe, expect, it } from 'vitest'
import cases from '../../../spec/validation-cases.json'
import type { FieldMeta } from './types'
import { validateField } from './validation'

/**
 * Acceptance criterion of ROADMAP phase 10 (decision D15): for the same input the client reports the same rule codes
 * as the server. The server side runs the same file in core's ValidationCasesTest, which also proves that
 * `fields` is exactly what the server exports.
 */
interface Case {
  field: string
  value?: unknown
  insert?: boolean
  expect: string[]
}

const fields = new Map((cases.fields as unknown as FieldMeta[]).map((f) => [f.name, f]))
const dictionaries = Object.fromEntries(
  Object.entries(cases.dictionaries as Record<string, string[]>).map(([urn, codes]) => [urn, new Set(codes)]),
)
const now = new Date(cases.now)

describe('shared validation cases', () => {
  it('cover every exportable rule kind', () => {
    const kinds = new Set([...fields.values()].flatMap((f) => f.rules.map((r) => r.kind)))
    expect([...kinds].sort()).toEqual(['LENGTH', 'NOT_FUTURE', 'PATTERN', 'RANGE', 'REQUIRED', 'SCALE'])
  })

  ;(cases.cases as Case[]).forEach((c, index) => {
    const shown = 'value' in c ? JSON.stringify(c.value) : '(absent)'
    it(`#${index} ${c.field} = ${shown}${c.insert ? ' (insert)' : ''}`, () => {
      const field = fields.get(c.field)
      expect(field, `field ${c.field}`).toBeDefined()
      const codes = validateField(field!, 'value' in c ? c.value : undefined, {
        insert: c.insert ?? false,
        now,
        dictionaries,
      }).map((v) => v.ruleCode)
      expect(codes).toEqual(c.expect)
    })
  })
})
