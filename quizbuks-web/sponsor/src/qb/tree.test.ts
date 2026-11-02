import { describe, expect, it } from 'vitest'
import { DEFAULT_LAYOUT, edgesOf, layoutTree, levelsOf, progressOf, readAllRows, TREE_PAGE, treeStateOf } from './tree'

const node = (id: string, ...requires: string[]) => ({ id, name: id, requires: requires.map((r) => ({ id: r,
  requirement: 'PASSED' as const })) })

describe('the tree\'s layout (Q85)', () => {
  it('reads edges from the templates\' JSON, and states', () => {
    expect(edgesOf(JSON.stringify([{ requiredPublicationId: 'a', requirement: 'ANSWERED' },
      { requiredPublicationId: 'b', requirement: 'ODD' }, { nope: 1 }]))).toEqual([
      { id: 'a', requirement: 'ANSWERED' }, { id: 'b', requirement: 'PASSED' }])
    expect(edgesOf('{')).toEqual([])
    expect(edgesOf(null)).toEqual([])
    expect(treeStateOf('LIT')).toBe('LIT')
    expect(treeStateOf('odd')).toBeNull()
  })

  it('puts what requires nothing first and what requires others one level above the highest of them', () => {
    const levels = levelsOf([node('c', 'b'), node('a'), node('b', 'a'), node('d'), node('e', 'a', 'c'),
      node('f', 'unknown')])
    expect([...levels.entries()].sort()).toEqual([['a', 1], ['b', 2], ['c', 3], ['d', 1], ['e', 4], ['f', 1]])
    // A cycle does not loop.
    expect([...levelsOf([node('a', 'b'), node('b', 'a')]).keys()].sort()).toEqual(['a', 'b'])
  })

  it('places levels left to right or top to bottom, orders a level by what it requires, and draws each edge', () => {
    const nodes = [node('a'), node('d'), node('b', 'd'), node('c', 'a'), node('e', 'b', 'c')]
    const row = layoutTree(nodes, { ...DEFAULT_LAYOUT, direction: 'row' })
    expect(row.levels.map((l) => [l.level, l.nodes.map((n) => n.node.id)])).toEqual([
      [1, ['a', 'd']], [2, ['c', 'b']], [3, ['e']]])
    const a = row.nodes.find((n) => n.node.id === 'a')!
    const c = row.nodes.find((n) => n.node.id === 'c')!
    expect(c.x).toBeGreaterThan(a.x)
    expect(c.y).toBe(a.y)
    expect(row.edges.map((e) => `${e.from}>${e.to}`).sort()).toEqual(['a>c', 'b>e', 'c>e', 'd>b'])
    expect(row.edges[0].d).toMatch(/^M [\d.]+ [\d.]+ C /)
    expect(row.width).toBe(2 * DEFAULT_LAYOUT.margin + 3 * DEFAULT_LAYOUT.nodeWidth + 2 * DEFAULT_LAYOUT.gapBetweenLevels)
    expect(row.height).toBe(2 * DEFAULT_LAYOUT.margin + 2 * DEFAULT_LAYOUT.nodeHeight + DEFAULT_LAYOUT.gapWithinLevel)
    const column = layoutTree(nodes, { ...DEFAULT_LAYOUT, direction: 'column' })
    const ca = column.nodes.find((n) => n.node.id === 'a')!
    const cc = column.nodes.find((n) => n.node.id === 'c')!
    expect(cc.y).toBeGreaterThan(ca.y)
    expect(cc.x).toBe(ca.x)
    expect(layoutTree([], { ...DEFAULT_LAYOUT, direction: 'row' })).toMatchObject({ nodes: [], edges: [],
      width: 2 * DEFAULT_LAYOUT.margin, height: 2 * DEFAULT_LAYOUT.margin })
  })

  it('counts what is lit and names what could start next', () => {
    expect(progressOf([{ ...node('a'), state: 'LIT' }, { ...node('b', 'a'), state: 'UNLOCKED' },
      { ...node('c', 'b'), state: 'LOCKED' }])).toEqual({ lit: 1, total: 3, next: [expect.objectContaining({ id: 'b' })] })
  })

  it('reads every page of a large tree, stopping at a short page', async () => {
    const asked: number[] = []
    const rows = await readAllRows(async (offset, limit) => {
      asked.push(offset)
      const n = offset === 0 ? limit : offset === TREE_PAGE ? limit : 3
      return { items: Array.from({ length: n }, (_, i) => offset + i) }
    })
    expect(rows).toHaveLength(2 * TREE_PAGE + 3)
    expect(asked).toEqual([0, TREE_PAGE, 2 * TREE_PAGE])
  })
})
