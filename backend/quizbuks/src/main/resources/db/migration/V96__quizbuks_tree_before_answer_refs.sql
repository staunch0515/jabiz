-- F-S2d (docs/quizbuks/plans/impl/F-S2d.md §2): a node is named by its graph and its id together (ids are unique within
-- a graph only: an operator's copy keeps its template's ids). A condition still names nodes by id; when its version
-- goes into effect each reference is resolved to one graph: the graph's own when it has the node, else the one other
-- graph in effect it may name that has it (tree.TreeRules#resolve). The references keep the graph, and a node row's
-- before_answer becomes a JSON array of "<graph>/<node>". The rows in effect are rewritten here by the same rule; an
-- entry no single graph has stays a bare id, which nothing counts as lit and the daily check reports
-- (BEFORE_ANSWER_UNRESOLVED).
ALTER TABLE qb_tree_node_ref ADD COLUMN ref_tree_id varchar(36);

-- The graphs a graph may name nodes of (TreeProcesses' loadRules): a Sponsor's, the platform's templates lit by
-- Takers; a template, the platform's other templates lit by the same; an operator's copy, the operator's other copies.
UPDATE qb_tree_node_ref r SET ref_tree_id =
    CASE WHEN EXISTS (SELECT 1 FROM qb_tree_node own WHERE own.tree_id = r.tree_id AND own.node_id = r.ref_node_id)
         THEN r.tree_id
         ELSE (SELECT min(o.tree_id)
               FROM qb_tree g
               JOIN qb_tree o ON o.tree_id <> g.tree_id AND o.status = 'ACTIVE'
               JOIN qb_tree_node n ON n.tree_id = o.tree_id AND n.node_id = r.ref_node_id AND n.active
               WHERE g.tree_id = r.tree_id
                 AND ((g.owner_kind = 'SPONSOR' AND o.owner_kind = 'PLATFORM' AND o.subject_kind = 'TAKER')
                      OR (g.owner_kind = 'PLATFORM' AND o.owner_kind = 'PLATFORM' AND o.subject_kind = g.subject_kind)
                      OR (g.owner_kind = 'OPERATOR' AND o.owner_kind = 'OPERATOR' AND o.owner_id = g.owner_id))
               HAVING count(*) = 1)
    END;

-- Each entry of a node row's before_answer, by the node's references (one per node its condition names).
UPDATE qb_tree_node n SET before_answer = (
    SELECT jsonb_agg(coalesce(r.ref_tree_id || '/' || ba.node, ba.node) ORDER BY ba.ord)::text
    FROM jsonb_array_elements_text(CAST(n.before_answer AS jsonb)) WITH ORDINALITY AS ba(node, ord)
    LEFT JOIN qb_tree_node_ref r ON r.tree_id = n.tree_id AND r.node_id = n.node_id AND r.ref_node_id = ba.node)
WHERE n.before_answer IS NOT NULL;
