-- F-S4b (docs/quizbuks/plans/impl/F-S4.md §3): the platform-fee Kudos a Sponsor is granted for lighting a node. Set on a
-- platform template's node definition (copied into an operator's copy), carried on the node row of the version in effect,
-- and frozen on the lit record when lit (an append-only table: a column added, nothing renamed or changed).
ALTER TABLE qb_tree_node_def ADD COLUMN grant_amount numeric(19, 0);
ALTER TABLE qb_tree_node ADD COLUMN grant_amount numeric(19, 0);
ALTER TABLE qb_tree_lit_version ADD COLUMN grant_amount numeric(19, 0);
