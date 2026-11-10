-- F-S3f: the people who answered a publication bound to a node and have not lit it, counted by node once a day by the
-- job qb.tree-node-counts (the pages say "as of" the day; the templates show a count only from the statistics'
-- minimum group up). One row per node of a graph in effect lit by Takers, updated in place: the latest count only.
-- A table of its own, so that the daily count never changes the version of qb_tree_node (lighting and the editor
-- write that row with the version they read).
CREATE TABLE qb_tree_node_count (
    row_id          varchar(36)   PRIMARY KEY,
    tree_id         varchar(36)   NOT NULL REFERENCES qb_tree (tree_id),
    node_id         varchar(36)   NOT NULL,
    answered_unlit  numeric(9, 0) NOT NULL,
    counted_on      date          NOT NULL,
    version         bigint        NOT NULL,
    CONSTRAINT uk_qb_tree_node_count UNIQUE (tree_id, node_id)
);
