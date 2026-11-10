import { describe, expect, it } from 'vitest'
import { layout } from './layout'
import type { Condition, GraphNode } from './states'

function node(nodeId: string, condition: Condition, refsTree: string | null = null): GraphNode {
  const names = 'op' in condition ? condition.items.flatMap((i) => ('node' in i ? [i.node] : [])) : []
  return { nodeId, name: { en: nodeId }, description: null, condition, seq: 0, lit: false, unmet: [],
    refs: names.map((n) => ({ node: n, tree: refsTree, name: null, lit: false })), gates: [] }
}

describe('the automatic layout', () => {
  it('puts what must come first above, a node one level below the deepest it names', () => {
    const nodes = [
      node('a', { op: 'ALL', items: [{ passed: true }] }),
      node('b', { op: 'ALL', items: [{ node: 'a' }, { passed: true }] }, 't'),
      node('c', { op: 'ALL', items: [{ node: 'a' }, { node: 'b' }] }, 't'),
      node('d', { op: 'ANY', items: [{ passed: true }] }),
    ]
    const placed = layout(nodes, 't')
    expect(placed.layers).toEqual([['a', 'd'], ['b'], ['c']])
    expect(placed.edges).toEqual([{ from: 'a', to: 'b' }, { from: 'a', to: 'c' }, { from: 'b', to: 'c' }])
  })

  it('orders a level by the place of what it names, so lines cross less', () => {
    const nodes = [
      node('x', { op: 'ALL', items: [{ passed: true }] }),
      node('y', { op: 'ALL', items: [{ passed: true }] }),
      node('under-y', { op: 'ALL', items: [{ node: 'y' }] }, 't'),
      node('under-x', { op: 'ALL', items: [{ node: 'x' }] }, 't'),
    ]
    expect(layout(nodes, 't').layers).toEqual([['x', 'y'], ['under-x', 'under-y']])
  })

  it('draws no line to another map\'s node, and reads a draft\'s condition when nothing is resolved', () => {
    const crossing = node('k', { op: 'ALL', items: [{ node: 'elsewhere' }, { passed: true }] }, 'platform')
    expect(layout([crossing], 't').edges).toEqual([])
    const draft = [node('p', { op: 'ALL', items: [{ passed: true }] }),
      { ...node('q', { op: 'ALL', items: [{ node: 'p' }] }), refs: [] }]
    expect(layout(draft).layers).toEqual([['p'], ['q']])
  })

  it('cuts a cycle rather than looping (the server never sends one)', () => {
    const nodes = [node('a', { op: 'ALL', items: [{ node: 'b' }] }, 't'), node('b', { op: 'ALL', items: [{ node: 'a' }] }, 't')]
    expect(layout(nodes, 't').layers.flat().sort()).toEqual(['a', 'b'])
  })
})
