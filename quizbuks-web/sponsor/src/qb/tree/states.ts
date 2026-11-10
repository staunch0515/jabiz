import type { I18nText } from '../legal'

/**
 * A knowledge graph's node as a person sees it (F-S3, docs/quizbuks/plans/impl/F-S3.md §2): the server's
 * `tree.TreeNodeStates` and `tree.TreeRules.holds`, copied; both pass the cases of `tree-state-cases.json`
 * (`states.test.ts`, the backend's `TreeNodeStatesTest`). The facts come from `qb.app.tree`; whether the person can
 * start a publication bound to a node, from `qb.app.feed` with its `tree` parameter. Change the rule in both.
 */

/** A node's condition, as the server stores it (`TreeCondition.toMap`). */
export type Condition =
  | { op: 'ALL' | 'ANY'; items: Condition[] }
  | { op: 'AT_LEAST'; n: number; items: Condition[] }
  | { node: string; beforeAnswer?: boolean }
  | { passed: true; beforeAnswer?: boolean }

export const STATES = ['LIT', 'LOCKED', 'CAN_ANSWER', 'WAITING', 'MISSING'] as const
export type NodeState = (typeof STATES)[number]

export interface Facts {
  /** Lit now (a lit record with no revocation). */
  lit: boolean
  /** The nodes to be lit before answering that are not (`<graph>/<node>`). */
  unmet: string[]
  /** The person can start a publication bound to the node now. */
  startable: boolean
  condition: Condition
  /** The nodes the condition names that are lit now, by node id. */
  litRefs: ReadonlySet<string> | string[]
}

/** Whether the condition holds with these nodes lit and (or not) a pass on the node. */
export function holds(condition: Condition, lit: ReadonlySet<string>, passed: boolean): boolean {
  if ('op' in condition) {
    const met = condition.items.filter((item) => holds(item, lit, passed)).length
    if (condition.op === 'ALL') return met === condition.items.length
    if (condition.op === 'ANY') return met >= 1
    return met >= (condition.op === 'AT_LEAST' ? condition.n : 0)
  }
  if ('node' in condition) return lit.has(condition.node)
  return passed
}

/** The state the node shows, decided in this order. */
export function stateOf(facts: Facts): NodeState {
  if (facts.lit) return 'LIT'
  if (facts.unmet.length > 0) return 'LOCKED'
  const lit = facts.litRefs instanceof Set ? facts.litRefs : new Set(facts.litRefs)
  // A pass lights it only if everything else holds (a pass made but not yet evaluated counts the same).
  if (!holds(facts.condition, lit, true)) return 'MISSING'
  return facts.startable ? 'CAN_ANSWER' : 'WAITING'
}

/** A node the condition names, as the version in effect resolved it. */
export interface NodeRef {
  node: string
  tree: string | null
  name: I18nText | null
  lit: boolean
}

/** One node of a graph for the component: the facts, read from a row of `qb.app.tree`. */
export interface GraphNode {
  nodeId: string
  name: I18nText
  description: I18nText | null
  condition: Condition
  seq: number
  lit: boolean
  unmet: string[]
  refs: NodeRef[]
  /** The `qb.tree.gate.*` parameters that name the node: the platform features lighting it opens. */
  gates: string[]
}

/** A graph and its nodes. */
export interface Graph {
  treeId: string
  ownerKind: 'PLATFORM' | 'SPONSOR'
  ownerId: string | null
  name: I18nText
  description: I18nText | null
  nodes: GraphNode[]
}

function json<T>(value: unknown, fallback: T): T {
  if (value == null) return fallback
  if (typeof value !== 'string') return value as T
  try {
    return JSON.parse(value) as T
  } catch {
    return fallback
  }
}

/** The graph from the rows of `qb.app.tree` (one per node); null for no rows (not a graph the person may see). */
export function graphOf(rows: Record<string, unknown>[]): Graph | null {
  const first = rows[0]
  if (!first) return null
  return {
    treeId: String(first.treeId),
    ownerKind: first.ownerKind === 'PLATFORM' ? 'PLATFORM' : 'SPONSOR',
    ownerId: first.ownerId == null ? null : String(first.ownerId),
    name: json<I18nText>(first.treeName, {}),
    description: json<I18nText | null>(first.treeDescription, null),
    nodes: rows.map((row) => ({
      nodeId: String(row.nodeId),
      name: json<I18nText>(row.name, {}),
      description: json<I18nText | null>(row.description, null),
      condition: json<Condition>(row.condition, { op: 'ALL', items: [] }),
      seq: Number(row.seq ?? 0),
      lit: row.lit === true,
      unmet: json<string[]>(row.unmet, []),
      refs: json<NodeRef[]>(row.refs, []),
      gates: typeof row.gates === 'string' && row.gates ? row.gates.split(',') : [],
    })).sort((a, b) => a.seq - b.seq),
  }
}

/** Each node's state, given the nodes the person can start a publication of now. */
export function statesOf(graph: Graph, startable: ReadonlySet<string>): Map<string, NodeState> {
  return new Map(graph.nodes.map((node) => [node.nodeId, stateOf({
    lit: node.lit,
    unmet: node.unmet,
    startable: startable.has(node.nodeId),
    condition: node.condition,
    litRefs: new Set(node.refs.filter((ref) => ref.lit).map((ref) => ref.node)),
  })]))
}
