/*
 * The Sponsor's knowledge map page (F-S3c, docs/quizbuks/plans/impl/F-S3.md §4; Q85's tree page before): the reads
 * are the shared `qb/tree/api.ts` (`qb.sponsor.trees`, `qb.sponsor.tree`). The page stays at its address; after F-F3
 * it moves under the publishing area's "map" tab.
 */
import { MAP_PATH } from '../shell/paths'

export const TREE_PATH = MAP_PATH

/** The map page with a node marked (a publication bound to it links there); the page without one. */
export const treePathOf = (nodeId: string | null | undefined) =>
  nodeId ? `${TREE_PATH}?node=${encodeURIComponent(nodeId)}` : TREE_PATH
