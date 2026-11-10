import { describe, expect, it } from 'vitest'
import cases from './tree-state-cases.json'
import { type Condition, graphOf, stateOf, statesOf } from './states'

describe('the five states a node shows (the server passes the same cases)', () => {
  it.each(cases.cases.map((c) => [c.name, c] as const))('%s', (_name, c) => {
    expect(stateOf({ lit: c.lit, unmet: c.unmet, startable: c.startable, condition: c.condition as Condition,
      litRefs: c.litRefs })).toBe(c.state)
  })

  it('covers every state', () => {
    expect(new Set(cases.cases.map((c) => c.state))).toEqual(new Set(['LIT', 'LOCKED', 'CAN_ANSWER', 'WAITING',
      'MISSING']))
  })
})

describe('the graph from the rows of qb.app.tree', () => {
  const rows = [
    { treeId: 't', ownerKind: 'SPONSOR', ownerId: 'u', treeName: '{"en":"Coffee"}', treeDescription: null,
      nodeId: 'b', name: '{"en":"Advanced"}', description: null, seq: 2, lit: false,
      condition: '{"op":"ALL","items":[{"node":"a","beforeAnswer":true},{"passed":true}]}',
      unmet: '["t/a"]', refs: '[{"node":"a","tree":"t","name":{"en":"Basics"},"lit":false}]', gates: null },
    { treeId: 't', ownerKind: 'SPONSOR', ownerId: 'u', treeName: '{"en":"Coffee"}', treeDescription: null,
      nodeId: 'a', name: '{"en":"Basics"}', description: '{"en":"Start here"}', seq: 1, lit: true,
      condition: '{"op":"ALL","items":[{"passed":true}]}', unmet: '[]', refs: '[]',
      gates: 'qb.tree.gate.referrer-node' },
  ]

  it('reads the facts in the author\'s order', () => {
    const graph = graphOf(rows)!
    expect(graph.name).toEqual({ en: 'Coffee' })
    expect(graph.nodes.map((n) => n.nodeId)).toEqual(['a', 'b'])
    expect(graph.nodes[0].gates).toEqual(['qb.tree.gate.referrer-node'])
    expect(graph.nodes[1].unmet).toEqual(['t/a'])
    expect(statesOf(graph, new Set(['b']))).toEqual(new Map([['a', 'LIT'], ['b', 'LOCKED']]))
  })

  it('is no graph without rows', () => {
    expect(graphOf([])).toBeNull()
  })
})
