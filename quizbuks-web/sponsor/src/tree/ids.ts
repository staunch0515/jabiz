/**
 * The names the console's tree page calls on the server (Q85, docs/quizbuks/plans/Q85-tech-tree.md). Only the console
 * reads the sponsor's whole tree, so they live here rather than in the shared `src/qb/ids.ts`.
 */
export const TREE_TEMPLATE = {
  /** Every submitted publication as a node, with its counts. */
  tree: 'qb.sponsor.tree',
  /** The customers on one node in one state. */
  people: 'qb.sponsor.tree-people',
  /** One customer's state on every node, by the transactions page's key. */
  person: 'qb.sponsor.tree-person',
} as const
