/**
 * A sponsor's tree (Q85, docs/quizbuks/plans/Q85-tech-tree.md): the nodes are publications, the edges what a
 * publication requires first (Q76), and a person stands on each node in one of six states (`tree.TreeStates` on the
 * server, the one definition; the pages only draw what the templates say). This file lays the tree out: levels as
 * on the sponsor's page (what requires nothing first, what requires others one level above the highest of them),
 * then a position for every node. No drawing library: the picture is inline SVG (`TechTree.tsx`).
 */

export type TreeState = 'LIT' | 'ANSWERED' | 'IN_PROGRESS' | 'UNLOCKED' | 'LOCKED' | 'CLOSED'

export const TREE_STATES: readonly TreeState[] = ['LIT', 'ANSWERED', 'IN_PROGRESS', 'UNLOCKED', 'LOCKED', 'CLOSED']

export function treeStateOf(value: unknown): TreeState | null {
  return typeof value === 'string' && (TREE_STATES as readonly string[]).includes(value) ? value as TreeState : null
}

export type Requirement = 'PASSED' | 'ANSWERED'

/** An edge: the node requires another first, passed or merely answered. */
export interface TreeEdge {
  id: string
  requirement: Requirement
}

/** What the layout needs of a node; the pages add what they show. */
export interface TreeNodeInput {
  id: string
  name: string
  requires: TreeEdge[]
}

/** Edges as the templates give them (`prerequisites` JSON: `requiredPublicationId`, `requirement`); none on bad JSON. */
export function edgesOf(value: unknown): TreeEdge[] {
  let raw: unknown = value
  if (typeof raw === 'string') {
    try {
      raw = JSON.parse(raw)
    } catch {
      return []
    }
  }
  if (!Array.isArray(raw)) return []
  return raw.flatMap((item): TreeEdge[] => {
    if (!item || typeof item !== 'object') return []
    const p = item as Record<string, unknown>
    if (typeof p.requiredPublicationId !== 'string') return []
    return [{ id: p.requiredPublicationId, requirement: p.requirement === 'ANSWERED' ? 'ANSWERED' : 'PASSED' }]
  })
}

/**
 * The level of every node, 1 for one requiring none of the others: one requiring others is one level above the
 * highest of them. Only nodes of the tree count (an edge to an unknown node is ignored); a cycle (none expected: the
 * server refuses one) breaks at the node first met.
 */
export function levelsOf<T extends TreeNodeInput>(nodes: readonly T[]): Map<string, number> {
  const known = new Map<string, number>()
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const levelOf = (id: string, walking: Set<string>): number => {
    const found = known.get(id)
    if (found !== undefined) return found
    const node = byId.get(id)
    if (!node || walking.has(id)) return 1
    walking.add(id)
    const required = node.requires.map((edge) => edge.id).filter((other) => other !== id && byId.has(other))
    const level = 1 + Math.max(0, ...required.map((other) => levelOf(other, walking)))
    walking.delete(id)
    known.set(id, level)
    return level
  }
  for (const node of nodes) levelOf(node.id, new Set())
  return known
}

export type Direction = 'row' | 'column'

export interface PlacedNode<T> {
  node: T
  level: number
  /** The node's place within its level, 0 first. */
  index: number
  x: number
  y: number
}

export interface PlacedEdge {
  from: string
  to: string
  requirement: Requirement
  /** The path's `d`, from the required node to the one requiring it. */
  d: string
}

export interface TreeLayout<T> {
  nodes: PlacedNode<T>[]
  edges: PlacedEdge[]
  levels: { level: number; nodes: PlacedNode<T>[] }[]
  width: number
  height: number
}

export interface LayoutOptions {
  direction: Direction
  nodeWidth: number
  nodeHeight: number
  /** The gap between levels, and between nodes within a level. */
  gapBetweenLevels: number
  gapWithinLevel: number
  /** The margin around the picture. */
  margin: number
}

export const DEFAULT_LAYOUT: Omit<LayoutOptions, 'direction'> = {
  nodeWidth: 176, nodeHeight: 72, gapBetweenLevels: 56, gapWithinLevel: 16, margin: 8,
}

/**
 * Where every node and edge goes. Levels go left to right (`row`) or top to bottom (`column`); within a level the
 * nodes follow the mean position of what they require (fewer crossings), then the name, then the id.
 */
export function layoutTree<T extends TreeNodeInput>(nodes: readonly T[], options: LayoutOptions): TreeLayout<T> {
  const levels = levelsOf(nodes)
  const byLevel = new Map<number, T[]>()
  for (const node of nodes) {
    const level = levels.get(node.id) ?? 1
    byLevel.set(level, [...(byLevel.get(level) ?? []), node])
  }
  const placed = new Map<string, PlacedNode<T>>()
  const columns: { level: number; nodes: PlacedNode<T>[] }[] = []
  const across = options.direction === 'row' ? options.nodeHeight : options.nodeWidth
  const along = options.direction === 'row' ? options.nodeWidth : options.nodeHeight
  for (const level of [...byLevel.keys()].sort((a, b) => a - b)) {
    const members = byLevel.get(level)!
    const keyOf = (node: T): number => {
      const positions = node.requires.map((edge) => placed.get(edge.id)?.index).filter((i): i is number => i !== undefined)
      return positions.length === 0 ? Number.POSITIVE_INFINITY
        : positions.reduce((sum, i) => sum + i, 0) / positions.length
    }
    const ordered = members.map((node) => ({ node, key: keyOf(node) })).sort((a, b) => a.key - b.key
      || a.node.name.localeCompare(b.node.name) || a.node.id.localeCompare(b.node.id)).map((item) => item.node)
    const column: PlacedNode<T>[] = ordered.map((node, index) => {
      const alongLevel = options.margin + (level - 1) * (along + options.gapBetweenLevels)
      const acrossLevel = options.margin + index * (across + options.gapWithinLevel)
      const place: PlacedNode<T> = options.direction === 'row'
        ? { node, level, index, x: alongLevel, y: acrossLevel }
        : { node, level, index, x: acrossLevel, y: alongLevel }
      placed.set(node.id, place)
      return place
    })
    columns.push({ level, nodes: column })
  }
  const edges: PlacedEdge[] = []
  for (const node of nodes) {
    const to = placed.get(node.id)!
    for (const edge of node.requires) {
      const from = placed.get(edge.id)
      if (!from || from.node.id === node.id) continue
      edges.push({ from: from.node.id, to: node.id, requirement: edge.requirement,
        d: options.direction === 'row'
          ? curve(from.x + options.nodeWidth, from.y + options.nodeHeight / 2, to.x, to.y + options.nodeHeight / 2, true)
          : curve(from.x + options.nodeWidth / 2, from.y + options.nodeHeight, to.x + options.nodeWidth / 2, to.y, false) })
    }
  }
  const deepest = Math.max(0, ...columns.map((column) => column.nodes.length))
  const levelCount = columns.length
  const alongSize = levelCount === 0 ? 0 : levelCount * along + (levelCount - 1) * options.gapBetweenLevels
  const acrossSize = deepest === 0 ? 0 : deepest * across + (deepest - 1) * options.gapWithinLevel
  return {
    nodes: [...placed.values()],
    edges,
    levels: columns,
    width: 2 * options.margin + (options.direction === 'row' ? alongSize : acrossSize),
    height: 2 * options.margin + (options.direction === 'row' ? acrossSize : alongSize),
  }
}

function curve(x1: number, y1: number, x2: number, y2: number, horizontal: boolean): string {
  const r = (n: number) => Math.round(n * 10) / 10
  if (horizontal) {
    const mid = (x1 + x2) / 2
    return `M ${r(x1)} ${r(y1)} C ${r(mid)} ${r(y1)}, ${r(mid)} ${r(y2)}, ${r(x2)} ${r(y2)}`
  }
  const mid = (y1 + y2) / 2
  return `M ${r(x1)} ${r(y1)} C ${r(x1)} ${r(mid)}, ${r(x2)} ${r(mid)}, ${r(x2)} ${r(y2)}`
}

/** More nodes than this are drawn by level, each level folded until opened (D-Q85-9). */
export const FOLD_ABOVE = 60

/** How many of the tree's nodes a person lit, and which open nodes they could start next. */
export function progressOf<T extends TreeNodeInput & { state?: TreeState | null }>(nodes: readonly T[]):
  { lit: number; total: number; next: T[] } {
  return {
    lit: nodes.filter((node) => node.state === 'LIT').length,
    total: nodes.length,
    next: nodes.filter((node) => node.state === 'UNLOCKED'),
  }
}

/** The most rows a template answers at once (the platform's page limit): a tree reads page after page. */
export const TREE_PAGE = 100

/** How many pages a tree reads at most (a sponsor with more than this many publications is nobody we expect). */
export const TREE_PAGES = 20

/**
 * Every row of a template, read page by page until a short page (the platform caps a page at TREE_PAGE whatever the
 * limit asked, so one read would cut a large tree silently).
 */
export async function readAllRows<Row>(readPage: (offset: number, limit: number) => Promise<{ items: Row[] }>):
  Promise<Row[]> {
  const all: Row[] = []
  for (let page = 0; page < TREE_PAGES; page++) {
    const { items } = await readPage(page * TREE_PAGE, TREE_PAGE)
    all.push(...items)
    if (items.length < TREE_PAGE) break
  }
  return all
}
