import { describe, expect, it } from 'vitest'
import { levelsOf } from './levels'

const pub = (publicationId: string, ...required: string[]) => ({ publicationId,
  prerequisites: JSON.stringify(required.map((id) => ({ requiredPublicationId: id, requirement: 'PASSED' }))) })

describe('the levels of a sponsor\'s page (Q76)', () => {
  it('puts what requires nothing first and what requires others above them, keeping the order within a level', () => {
    const levels = levelsOf([pub('c', 'b'), pub('a'), pub('b', 'a'), pub('d'), pub('e', 'a', 'c')])
    expect(levels.map((l) => [l.level, l.items.map((i) => i.publicationId)])).toEqual([
      [1, ['a', 'd']], [2, ['b']], [3, ['c']], [4, ['e']]])
  })

  it('counts listed publications only, and one level when nothing is required', () => {
    expect(levelsOf([pub('a', 'unlisted'), pub('b')]).map((l) => l.level)).toEqual([1])
    expect(levelsOf([])).toEqual([])
    // Bad JSON or none: nothing required.
    expect(levelsOf([{ publicationId: 'a', prerequisites: '{' }, { publicationId: 'b' }])).toEqual([
      { level: 1, items: [{ publicationId: 'a', prerequisites: '{' }, { publicationId: 'b' }] }])
  })

  it('does not loop on a cycle', () => {
    const levels = levelsOf([pub('a', 'b'), pub('b', 'a'), pub('c', 'a')])
    expect(levels.flatMap((l) => l.items.map((i) => i.publicationId)).sort()).toEqual(['a', 'b', 'c'])
    expect(levels.at(-1)!.items.map((i) => i.publicationId)).toEqual(['c'])
  })
})
