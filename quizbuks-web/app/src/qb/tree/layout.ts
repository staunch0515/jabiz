import type { Condition, GraphNode } from './states'

/**
 * Where the graph component draws each node (F-S3: positions are laid out automatically; dragging and saved positions
 * are not in the first launch). A node sits one layer below the deepest node of the same graph its condition names
 * (the longest path from a start: graphs have no cycles, the server refuses them), so that what must come first is
 * always above; within a layer, nodes are ordered by the mean place of what they name (barycentre), then by the
 * author's order, which keeps lines from crossing in the common cases. References to another graph's nodes do not
 * place a node: they are shown on it.
 */

export interface Placed {
  nodeId: string
  layer: number
  /** The place within the layer, from 0. */
  order: number
}

export interface Layout {
  placed: Map<string, Placed>
  /** The layers, top to bottom, each the node ids in order. */
  layers: string[][]
  /** Lines from a named node (above) to the node naming it (below), in the same graph. */
  edges: { from: string; to: string }[]
}

/** The node ids a condition names. */
export function named(condition: Condition): string[] {
  if ('op' in condition) return condition.items.flatMap(named)
  if ('node' in condition) return [condition.node]
  return []
}

/**
 * @param treeId the graph's id: a reference the version in effect resolved to it is a line; without resolved references
 *               (a draft in the editor) the condition's node ids found in the graph are
 */
export function layout(nodes: GraphNode[], treeId?: string): Layout {
  const ids = new Set(nodes.map((node) => node.nodeId))
  const seq = new Map(nodes.map((node, index) => [node.nodeId, index]))
  const parents = new Map(nodes.map((node) => {
    const inGraph = node.refs.length > 0
      ? node.refs.filter((ref) => ref.tree === treeId && ids.has(ref.node)).map((ref) => ref.node)
      : named(node.condition).filter((id) => ids.has(id))
    return [node.nodeId, [...new Set(inGraph)]]
  }))
  const depth = new Map<string, number>()
  const visiting = new Set<string>()
  const depthOf = (id: string): number => {
    const known = depth.get(id)
    if (known !== undefined) return known
    // A cycle never reaches the page (the server refuses one); should one appear, it is cut rather than looped.
    if (visiting.has(id)) return 0
    visiting.add(id)
    const value = Math.max(-1, ...(parents.get(id) ?? []).map(depthOf)) + 1
    visiting.delete(id)
    depth.set(id, value)
    return value
  }
  nodes.forEach((node) => depthOf(node.nodeId))

  const layers: string[][] = []
  for (const node of nodes) {
    const layer = depth.get(node.nodeId) ?? 0
    ;(layers[layer] ??= []).push(node.nodeId)
  }
  const placed = new Map<string, Placed>()
  layers.forEach((layer, index) => {
    if (index > 0) {
      const mean = (id: string) => {
        const above = (parents.get(id) ?? []).map((parent) => placed.get(parent)?.order).filter(
          (order): order is number => order !== undefined)
        return above.length === 0 ? Number.POSITIVE_INFINITY : above.reduce((a, b) => a + b, 0) / above.length
      }
      layer.sort((a, b) => {
        const ma = mean(a)
        const mb = mean(b)
        return (ma === mb ? 0 : ma < mb ? -1 : 1) || (seq.get(a) ?? 0) - (seq.get(b) ?? 0)
      })
    }
    layer.forEach((id, order) => placed.set(id, { nodeId: id, layer: index, order }))
  })
  const edges = nodes.flatMap((node) => (parents.get(node.nodeId) ?? []).map((from) => ({ from, to: node.nodeId })))
  return { placed, layers, edges }
}
