/*
 * The sponsor's tree page (Q85). F-S2c2 (D5) removed its templates (qb.sponsor.tree, -people, -person) with the old
 * tree; the page stays at its address, saying that it is being updated, until F-S3 shows the knowledge maps.
 */
import { MAP_PATH } from '../shell/paths'

export const TREE_PATH = MAP_PATH

/** The tree page with a node marked (the publication page links to it). */
export const treePathOf = (publicationId: string) => `${TREE_PATH}?node=${encodeURIComponent(publicationId)}`
