import { describe, expect, it } from 'vitest'
import { actionShown, actionsFor, needsOnlyTheKey } from './actions'
import type { ProcessEntry } from './types'

function process(name: string, extra: Partial<ProcessEntry> = {}): ProcessEntry {
  return {
    name,
    version: 1,
    latest: true,
    deprecated: false,
    label: name,
    description: '',
    input: { type: 'object', properties: { storyId: { type: 'string' } } },
    actsOn: { entity: 'Story', input: 'storyId', when: { field: 'status', values: ['DRAFT'] } },
    ...extra,
  } as ProcessEntry
}

describe('row actions', () => {
  it('are the latest processes acting on the entity', () => {
    const processes = [
      process('SUBMIT'),
      process('OLD_SUBMIT', { latest: false }),
      process('RETIRED', { deprecated: true }),
      process('OTHER', { actsOn: { entity: 'Order', input: 'orderId' } }),
      process('PLAIN', { actsOn: undefined }),
    ]
    expect(actionsFor('Story', processes).map((p) => p.name)).toEqual(['SUBMIT'])
  })

  it('are shown while the condition holds, and always without one', () => {
    expect(actionShown(process('SUBMIT'), { status: 'DRAFT' })).toBe(true)
    expect(actionShown(process('SUBMIT'), { status: 'PUBLISHED' })).toBe(false)
    expect(actionShown(process('SUBMIT'), {})).toBe(false)
    expect(actionShown(process('ANY', { actsOn: { entity: 'Story', input: 'storyId' } }), {})).toBe(true)
    const flag = process('FLAG', { actsOn: { entity: 'Story', input: 'storyId', when: { field: 'active', values: ['true'] } } })
    expect(actionShown(flag, { active: true })).toBe(true)
    expect(actionShown(flag, { active: false })).toBe(false)
  })

  it('run without a form when the key is their only input', () => {
    expect(needsOnlyTheKey(process('SUBMIT'))).toBe(true)
    const withComment = process('REJECT', {
      input: { type: 'object', properties: { storyId: { type: 'string' }, comment: { type: 'string' } } },
    })
    expect(needsOnlyTheKey(withComment)).toBe(false)
  })
})
