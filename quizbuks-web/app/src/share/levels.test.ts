import { describe, expect, it } from 'vitest'
import { grouped, layersOf, parsePagePrerequisites } from './levels'

const p = (publicationId: string, ...required: string[]) => ({ publicationId,
  prerequisites: required.map((id) => ({ publicationId: id, requirement: 'PASSED' as const })) })

describe("the sponsor page's layers (Q76)", () => {
  it('keeps one layer when nothing requires anything', () => {
    expect(grouped([p('a'), p('b')]).map((g) => g.map((x) => x.publicationId))).toEqual([['a', 'b']])
  })

  it('puts each one below the deepest it requires: a chain and a diamond', () => {
    const layers = layersOf([p('d', 'b', 'c'), p('b', 'a'), p('c', 'a'), p('a'), p('e', 'd')])
    expect(Object.fromEntries(layers)).toEqual({ a: 0, b: 1, c: 1, d: 2, e: 3 })
    expect(grouped([p('d', 'b', 'c'), p('b', 'a'), p('c', 'a'), p('a')]).map((g) => g.map((x) => x.publicationId)))
      .toEqual([['a'], ['b', 'c'], ['d']])
  })

  it('counts a prerequisite not on the page as none, and cuts a cycle', () => {
    expect(Object.fromEntries(layersOf([p('b', 'gone'), p('c', 'b')]))).toEqual({ b: 0, c: 1 })
    const cycle = layersOf([p('x', 'y'), p('y', 'x')])
    expect([...cycle.values()].sort()).toEqual([0, 1])
  })

  it("reads the template's column", () => {
    expect(parsePagePrerequisites('[{"publicationId":"a","requirement":"ANSWERED"}]'))
      .toEqual([{ publicationId: 'a', requirement: 'ANSWERED' }])
    expect(parsePagePrerequisites(null)).toEqual([])
    expect(parsePagePrerequisites('nope')).toEqual([])
  })
})
