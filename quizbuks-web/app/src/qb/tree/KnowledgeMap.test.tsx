import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import '../../messages'
import { KnowledgeMap } from './KnowledgeMap'
import type { Graph, NodeState } from './states'

const GRAPH: Graph = {
  treeId: 't', ownerKind: 'SPONSOR', ownerId: 'u', name: { en: 'Coffee' }, description: { en: 'Learn coffee' },
  nodes: [
    { nodeId: 'a', name: { en: 'Basics' }, description: { en: 'Know the beans' }, seq: 1, lit: true, unmet: [],
      condition: { op: 'ALL', items: [{ passed: true }] }, refs: [], gates: ['qb.tree.gate.referrer-node'] },
    { nodeId: 'b', name: { en: 'Advanced' }, description: null, seq: 2, lit: false, unmet: [],
      condition: { op: 'ALL', items: [{ node: 'a', beforeAnswer: true }, { passed: true }] },
      refs: [{ node: 'a', tree: 't', name: { en: 'Basics' }, lit: true }], gates: [] },
    { nodeId: 'c', name: { en: 'Expert' }, description: null, seq: 3, lit: false, unmet: [],
      condition: { op: 'ALL', items: [{ node: 'a' }, { node: 'b' }] },
      refs: [{ node: 'a', tree: 't', name: { en: 'Basics' }, lit: true },
        { node: 'b', tree: 't', name: { en: 'Advanced' }, lit: false }], gates: [] },
    { nodeId: 'd', name: { en: 'Roasting' }, description: null, seq: 4, lit: false, unmet: ['t/c'],
      condition: { op: 'ALL', items: [{ node: 'c', beforeAnswer: true }, { passed: true }] },
      refs: [{ node: 'c', tree: 't', name: { en: 'Expert' }, lit: false }], gates: [] },
  ],
}
const STATES = new Map<string, NodeState>([['a', 'LIT'], ['b', 'CAN_ANSWER'], ['c', 'MISSING'], ['d', 'LOCKED']])

afterEach(cleanup)

describe('the knowledge map', () => {
  it('draws every node with its state in words and marks a Sponsor map', async () => {
    render(<KnowledgeMap graph={GRAPH} states={STATES} />)
    expect(screen.getByTestId('knowledge-map')).toHaveAttribute('data-owner-kind', 'SPONSOR')
    expect(screen.getByTestId('knowledge-map-kind')).toHaveTextContent('Sponsor map')
    const nodes = screen.getAllByTestId('knowledge-map-node')
    expect(nodes.map((n) => n.getAttribute('data-state'))).toEqual(['LIT', 'CAN_ANSWER', 'MISSING', 'LOCKED'])
    expect(screen.getByRole('button', { name: 'Basics: Lit' })).toBeInTheDocument()
    await expectAccessible()
  })

  it('is one tab stop whose arrow keys move between nodes, and Enter shows a node', async () => {
    const user = userEvent.setup()
    render(<KnowledgeMap graph={GRAPH} states={STATES} />)
    await user.tab()
    await user.tab()
    await user.tab()
    await user.tab()
    expect(screen.getByRole('button', { name: 'Basics: Lit' })).toHaveFocus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('button', { name: 'Advanced: Can answer' })).toHaveFocus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('button', { name: 'Expert: Conditions missing' })).toHaveFocus()
    await user.keyboard('{ArrowUp}{ArrowUp}{Enter}')
    const details = screen.getByTestId('knowledge-map-details')
    expect(details).toHaveTextContent('Know the beans')
    expect(details).toHaveTextContent('You can recommend quizzes to friends.')
    await expectAccessible()
  })

  it('links what can be answered, lists what a locked node needs and marks what is missing', async () => {
    const user = userEvent.setup()
    render(<KnowledgeMap graph={GRAPH} states={STATES}
      startable={new Map([['b', [{ publicationId: 'p1', name: 'Advanced quiz' }]]])}
      publicationLink={(p) => <a href={`/p/${p.publicationId}`}>{p.name}</a>} />)
    await user.click(screen.getByRole('button', { name: 'Advanced: Can answer' }))
    expect(within(screen.getByTestId('knowledge-map-startable')).getByRole('link', { name: 'Advanced quiz' }))
      .toHaveAttribute('href', '/p/p1')
    await user.click(screen.getByRole('button', { name: 'Roasting: Locked' }))
    expect(screen.getByTestId('knowledge-map-unmet')).toHaveTextContent('Expert')
    await user.click(screen.getByRole('button', { name: 'Expert: Conditions missing' }))
    const condition = screen.getByTestId('knowledge-map-condition')
    expect(condition).toHaveTextContent('Light “Basics” — lit')
    expect(condition).toHaveTextContent('Light “Advanced” — not lit')
    await expectAccessible()
  })

  it('has a list view of the same nodes by level, and shows lit counts on a Sponsor\'s own map', async () => {
    const user = userEvent.setup()
    render(<KnowledgeMap graph={{ ...GRAPH, ownerKind: 'PLATFORM' }} litCounts={new Map([['a', 12], ['b', null]])} />)
    expect(screen.getByTestId('knowledge-map-kind')).toHaveTextContent('Platform map')
    await user.click(screen.getByTestId('knowledge-map-view-list'))
    expect(screen.getByRole('heading', { name: 'Level 1' })).toBeInTheDocument()
    const listed = screen.getAllByTestId('knowledge-map-list-node')
    expect(listed.map((n) => n.getAttribute('data-node-id'))).toEqual(['a', 'b', 'c', 'd'])
    // Nobody's states: no badge.
    expect(listed.map((n) => n.getAttribute('data-state'))).toEqual(['', '', '', ''])
    await user.click(within(listed[0]).getByRole('button', { name: 'Basics' }))
    expect(screen.getByTestId('knowledge-map-lit-count')).toHaveTextContent('12 Takers lit it')
    await user.click(within(listed[1]).getByRole('button', { name: 'Advanced' }))
    expect(screen.getByTestId('knowledge-map-lit-count')).toHaveTextContent('Fewer Takers than shown in statistics')
    await expectAccessible()
  })

  it('says an empty map has no nodes', async () => {
    render(<KnowledgeMap graph={{ ...GRAPH, nodes: [] }} />)
    expect(screen.getByTestId('knowledge-map-empty')).toBeInTheDocument()
    await expectAccessible()
  })
})
