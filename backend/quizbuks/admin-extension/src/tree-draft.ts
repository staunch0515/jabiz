/** A multilingual text by language. */
export type I18nText = Record<string, string>

/** A node's condition as the server stores it (`TreeCondition.toMap`). */
export type Condition =
  | { op: 'ALL' | 'ANY'; items: Condition[] }
  | { op: 'AT_LEAST'; n: number; items: Condition[] }
  | { node: string; beforeAnswer?: boolean }
  | { passed: true; beforeAnswer?: boolean }

/*
 * Copied from the Sponsor app's `sponsor/src/tree/draft.ts` (the extension cannot import the app's code; F-S3 §6:
 * no third copy of the map component, but the same rules for the text-list editor). Change both together.
 *
 * The knowledge map editor's draft (F-S3d, docs/quizbuks/plans/impl/F-S3.md §5): what the page edits and sends with
 * `QB_TREE_DRAFT_SAVE` (`TreeProcesses.SaveInput`: the whole draft, every node, in order). The editor offers only what
 * `tree.TreeRules` accepts in a condition's shape: "before answering" only on a node of the outermost "all"; a pass
 * at most once, never before answering; an "at least n" with 1 ≤ n ≤ its items. What depends on the other graphs
 * (cycles, depth, unknown nodes) the server says after saving (`problems`, by node).
 */

/** One node as the editor holds it: an existing one by its id, a new one by a key of this page. */
export interface DraftNode {
  /** The server's id; null for a node not saved yet. */
  nodeId: string | null
  /** A new node's key in this save (the server replaces it by an id); the node id for a saved one. */
  key: string
  name: I18nText
  description: I18nText | null
  condition: Condition
  /** Publications bound to it and not ended (from the version in effect): it cannot be removed until they end. */
  runningBound: number
  /**
   * F-S4b: the platform fee Kudos lighting it grants, as the version read has it (null: none). The editor has no field
   * for it yet (F-S4c) and sends it back as read, so that a save never drops it.
   */
  grantAmount?: number | null
}

export interface Draft {
  treeId: string
  /** The graph's revision the page read (`baseRevision`). */
  revision: number | null
  name: I18nText
  description: I18nText | null
  nodes: DraftNode[]
}

/** A new node's condition: lit by one pass of a publication bound to it. */
export const NEW_CONDITION: Condition = { op: 'ALL', items: [{ passed: true }] }

type Group = Extract<Condition, { op: string }>
type Item = Condition

export function isGroup(condition: Condition): condition is Group {
  return 'op' in condition
}

/** The outermost group of a condition (a lone item becomes the only item of an "all"). */
export function outer(condition: Condition): Group {
  return isGroup(condition) ? condition : { op: 'ALL', items: [condition] }
}

/** How many passes the condition asks for (anywhere). */
export function passes(condition: Condition): number {
  if (isGroup(condition)) return condition.items.reduce((sum, item) => sum + passes(item), 0)
  return 'passed' in condition ? 1 : 0
}

/** The nodes the condition names (anywhere), by id or key. */
export function references(condition: Condition): string[] {
  if (isGroup(condition)) return condition.items.flatMap(references)
  return 'node' in condition ? [condition.node] : []
}

/** Whether "before answering" may be set on an item of this group (only the outermost group, when it is "all"). */
export function beforeAnswerAllowed(group: Group, outermost: boolean): boolean {
  return outermost && group.op === 'ALL'
}

/** Whether a pass may be added somewhere in the condition (at most one). */
export function passAllowed(condition: Condition): boolean {
  return passes(condition) === 0
}

/**
 * The group with its operator changed. Leaving "all" at the top clears "before answering" on its items (only the
 * outermost "all" may carry it); "at least n" keeps n within its items.
 */
export function withOp(group: Group, op: Group['op'], outermost: boolean): Group {
  const items = outermost && op !== 'ALL' ? group.items.map(clearBeforeAnswer) : group.items
  if (op === 'AT_LEAST') {
    const n = group.op === 'AT_LEAST' ? group.n : 1
    return { op, n: clamp(n, items.length), items }
  }
  return { op, items }
}

/** The group's n set, kept within 1 and its items. */
export function withN(group: Group, n: number): Group {
  return group.op === 'AT_LEAST' ? { ...group, n: clamp(n, group.items.length) } : group
}

function clamp(n: number, count: number): number {
  return Math.max(1, Math.min(Math.trunc(n) || 1, Math.max(1, count)))
}

function clearBeforeAnswer(item: Item): Item {
  if (isGroup(item)) return item
  return 'node' in item ? { node: item.node } : { passed: true }
}

/** The group with an item added (a pass only when the whole condition has none; never "before answering"). */
export function withItem(group: Group, item: Item, whole: Condition): Group {
  if (!isGroup(item) && 'passed' in item && !passAllowed(whole)) return group
  const added = !isGroup(item) && 'passed' in item ? { passed: true as const } : item
  return fitN({ ...group, items: [...group.items, added] })
}

/** The group without its item at the index. */
export function withoutItem(group: Group, index: number): Group {
  return fitN({ ...group, items: group.items.filter((_, i) => i !== index) })
}

/** The group with its item at the index replaced. */
export function withItemAt(group: Group, index: number, item: Item): Group {
  return { ...group, items: group.items.map((old, i) => (i === index ? item : old)) }
}

/** A node item with "before answering" set or cleared (only where allowed; a pass never). */
export function withBeforeAnswer(item: Item, on: boolean, allowed: boolean): Item {
  if (isGroup(item) || !('node' in item)) return item
  if (on && allowed) return { node: item.node, beforeAnswer: true }
  return { node: item.node }
}

function fitN(group: Group): Group {
  return group.op === 'AT_LEAST' ? { ...group, n: clamp(group.n, group.items.length) } : group
}

/** The condition without the items naming the node (a group left empty stays, for the server to name). */
export function withoutReferences(condition: Condition, node: string): Condition {
  if (!isGroup(condition)) return condition
  return fitN({ ...condition, items: condition.items
    .filter((item) => isGroup(item) || !('node' in item) || item.node !== node)
    .map((item) => withoutReferences(item, node)) })
}

/** The nodes whose conditions name the node (to say what removing it changes). */
export function referrers(draft: Draft, key: string): DraftNode[] {
  return draft.nodes.filter((node) => node.key !== key && references(node.condition).includes(key))
}

/** The draft with a new node at the end; its key is unique in the draft. */
export function withNewNode(draft: Draft, name: I18nText): Draft {
  let n = draft.nodes.length + 1
  const keys = new Set(draft.nodes.map((node) => node.key))
  while (keys.has(`new-${n}`)) n++
  return { ...draft, nodes: [...draft.nodes, { nodeId: null, key: `new-${n}`, name, description: null,
    condition: NEW_CONDITION, runningBound: 0 }] }
}

/**
 * The draft without the node, and the other conditions without it. Null when it cannot be removed: publications
 * bound to it are still running (the server refuses the version with `QB_TREE_NODE_IN_USE`).
 */
export function withoutNode(draft: Draft, key: string): Draft | null {
  const node = draft.nodes.find((n) => n.key === key)
  if (!node || node.runningBound > 0) return null
  return { ...draft, nodes: draft.nodes.filter((n) => n.key !== key)
    .map((n) => ({ ...n, condition: withoutReferences(n.condition, key) })) }
}

/** The draft with one node moved up (-1) or down (+1); the order is the save's. */
export function withMoved(draft: Draft, key: string, by: -1 | 1): Draft {
  const from = draft.nodes.findIndex((n) => n.key === key)
  const to = from + by
  if (from < 0 || to < 0 || to >= draft.nodes.length) return draft
  const nodes = [...draft.nodes]
  ;[nodes[from], nodes[to]] = [nodes[to], nodes[from]]
  return { ...draft, nodes }
}

/** The draft with one node changed. */
export function withNode(draft: Draft, key: string, change: (node: DraftNode) => DraftNode): Draft {
  return { ...draft, nodes: draft.nodes.map((n) => (n.key === key ? change(n) : n)) }
}

/** `QB_TREE_DRAFT_SAVE`'s input (`TreeProcesses.SaveInput`). */
export interface SaveInput {
  treeId: string
  baseRevision: number | null
  name: I18nText
  description: I18nText | null
  nodes: { nodeId: string | null; key: string | null; name: I18nText; description: I18nText | null;
    condition: Condition; grantAmount: number | null }[]
}

export function saveInputOf(draft: Draft): SaveInput {
  return {
    treeId: draft.treeId,
    baseRevision: draft.revision,
    name: draft.name,
    description: draft.description,
    nodes: draft.nodes.map((node) => ({ nodeId: node.nodeId, key: node.nodeId ? null : node.key, name: node.name,
      description: node.description, condition: node.condition, grantAmount: node.grantAmount ?? null })),
  }
}

/**
 * The draft after a save: the server named the new nodes (`nodeIds` by key), so their keys and the conditions naming
 * them become the ids.
 */
export function afterSave(draft: Draft, nodeIds: Readonly<Record<string, string>>, revision: number): Draft {
  const rename = (condition: Condition): Condition => isGroup(condition)
    ? { ...condition, items: condition.items.map(rename) }
    : 'node' in condition && nodeIds[condition.node] ? { ...condition, node: nodeIds[condition.node] } : condition
  return { ...draft, revision, nodes: draft.nodes.map((node) => {
    const id = node.nodeId ?? nodeIds[node.key] ?? null
    return { ...node, nodeId: id, key: id ?? node.key, condition: rename(node.condition) }
  }) }
}


/** F-S3e, the extension's text list only. Each node's level: one more than the highest level of the nodes of this map its condition names (cycles cut). */
export function levelsOf(nodes: readonly DraftNode[]): Map<string, number> {
  const byKey = new Map(nodes.map((node) => [node.key, node]))
  const levels = new Map<string, number>()
  const visit = (key: string, path: Set<string>): number => {
    const known = levels.get(key)
    if (known !== undefined) return known
    const node = byKey.get(key)
    if (!node || path.has(key)) return 0
    path.add(key)
    const below = references(node.condition).filter((ref) => byKey.has(ref)).map((ref) => visit(ref, path))
    path.delete(key)
    const level = below.length === 0 ? 0 : Math.max(...below) + 1
    levels.set(key, level)
    return level
  }
  nodes.forEach((node) => visit(node.key, new Set()))
  return levels
}

/**
 * A node's grant as typed (F-S4c): empty is none; any other number is sent as it is, the server's rules
 * (`TreeRules.grantProblems`: a whole, positive amount within `qb.tree.grant.max-per-node`) answer for it.
 */
export function grantOf(typed: string): number | null {
  const text = typed.trim()
  if (text === '') return null
  const value = Number(text)
  return Number.isFinite(value) ? value : null
}
