import { describe, expect, it } from 'vitest'
import {
  afterSave, outer, passAllowed, referrers, saveInputOf, withBeforeAnswer, withItem, withMoved, withN, withNewNode,
  withOp, withoutItem, withoutNode, type Condition, type Draft,
} from './tree-draft'

const draft = (): Draft => ({
  treeId: 't1', revision: 3, name: { en: 'Baking' }, description: null,
  nodes: [
    { nodeId: 'a', key: 'a', name: { en: 'Bread' }, description: null, runningBound: 1,
      condition: { op: 'ALL', items: [{ passed: true }] } },
    { nodeId: 'b', key: 'b', name: { en: 'Sourdough' }, description: null, runningBound: 0,
      condition: { op: 'ALL', items: [{ node: 'a', beforeAnswer: true }, { passed: true }] } },
  ],
})

describe('the editor\'s condition rules (tree.TreeRules\' shape)', () => {
  it('allows one pass only, never before answering', () => {
    const group = outer({ op: 'ALL', items: [] })
    const once = withItem(group, { passed: true, beforeAnswer: true }, group)
    expect(once.items).toEqual([{ passed: true }])
    expect(passAllowed(once)).toBe(false)
    expect(withItem(once, { passed: true }, once)).toBe(once)
    expect(withBeforeAnswer({ passed: true }, true, true)).toEqual({ passed: true })
  })

  it('keeps "before answering" to the outermost "all"', () => {
    const top = draft().nodes[1].condition as Extract<Condition, { op: string }>
    expect(withOp(top, 'ANY', true).items).toEqual([{ node: 'a' }, { passed: true }])
    expect(withBeforeAnswer({ node: 'a' }, true, false)).toEqual({ node: 'a' })
    expect(withBeforeAnswer({ node: 'a' }, true, true)).toEqual({ node: 'a', beforeAnswer: true })
    // A nested group keeps what its items had (the outermost group is the only one that clears).
    expect(withOp(top, 'ANY', false).items[0]).toEqual({ node: 'a', beforeAnswer: true })
  })

  it('keeps "at least n" within 1 and its items', () => {
    const group = withOp(outer({ op: 'ALL', items: [{ node: 'a' }, { node: 'b' }] }), 'AT_LEAST', true)
    expect(group).toMatchObject({ op: 'AT_LEAST', n: 1 })
    expect(withN(group, 5)).toMatchObject({ n: 2 })
    expect(withN(group, 0)).toMatchObject({ n: 1 })
    expect(withoutItem(withN(group, 2), 0)).toMatchObject({ n: 1, items: [{ node: 'b' }] })
  })
})

describe('the draft', () => {
  it('adds a node with a key of its own and a pass as its condition', () => {
    const added = withNewNode(withNewNode(draft(), { en: 'Rye' }), { en: 'Spelt' })
    expect(added.nodes.slice(2).map((n) => [n.key, n.nodeId])).toEqual([['new-3', null], ['new-4', null]])
    expect(added.nodes[2].condition).toEqual({ op: 'ALL', items: [{ passed: true }] })
  })

  it('removes a node and the items naming it, but not one bound to running publications', () => {
    expect(withoutNode(draft(), 'a')).toBeNull()
    const free = { ...draft(), nodes: draft().nodes.map((n) => ({ ...n, runningBound: 0 })) }
    expect(referrers(free, 'a').map((n) => n.key)).toEqual(['b'])
    const removed = withoutNode(free, 'a')!
    expect(removed.nodes.map((n) => n.key)).toEqual(['b'])
    expect(removed.nodes[0].condition).toEqual({ op: 'ALL', items: [{ passed: true }] })
  })

  it('moves nodes within the order', () => {
    expect(withMoved(draft(), 'b', -1).nodes.map((n) => n.key)).toEqual(['b', 'a'])
    expect(withMoved(draft(), 'a', -1).nodes.map((n) => n.key)).toEqual(['a', 'b'])
  })

  it('sends new nodes by key, then takes the ids the server gave', () => {
    const added = withNewNode(draft(), { en: 'Rye' })
    const withRef = { ...added, nodes: added.nodes.map((n) => n.key === 'new-3'
      ? { ...n, condition: { op: 'ALL' as const, items: [{ node: 'b' }, { passed: true as const }] } }
      : n.key === 'b' ? { ...n, condition: { op: 'ANY' as const, items: [{ node: 'new-3' }] } } : n) }
    const input = saveInputOf(withRef)
    expect(input).toMatchObject({ treeId: 't1', baseRevision: 3 })
    expect(input.nodes.map((n) => [n.nodeId, n.key])).toEqual([['a', null], ['b', null], [null, 'new-3']])
    const saved = afterSave(withRef, { 'new-3': 'c' }, 4)
    expect(saved.revision).toBe(4)
    expect(saved.nodes[2]).toMatchObject({ nodeId: 'c', key: 'c' })
    expect(saved.nodes[1].condition).toEqual({ op: 'ANY', items: [{ node: 'c' }] })
  })
})
