/*---
id: qb.app.tree
description: "F-S3: one knowledge graph in effect as the caller sees it, node by node with the facts its state needs (tree.TreeNodeStates): lit, the prerequisites not lit, the condition and which of the nodes it names are lit, and the platform features it opens. Whether the caller can start a publication bound to a node comes from qb.app.feed with its tree parameter. Only graphs Takers light (the platform's and the Sponsors'), in effect; none for another graph."
entities: [SysParam, QbTree, QbTreeVersion, QbTreeNode, QbTreeNodeDef, QbTreeNodeRef, QbTreeLit, QbTreeLitRevocation]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeNodeDef: urn:jabiz:dataset:default:QbTreeNodeDef
  QbTreeNodeRef: urn:jabiz:dataset:default:QbTreeNodeRef
  QbTreeLit: urn:jabiz:dataset:me:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:me:QbTreeLitRevocation
params:
  treeId: { like: QbTree.treeId, required: true, description: The graph }
results:
  treeId:      { from: QbTree.treeId }
  ownerKind:   { from: QbTree.ownerKind }
  ownerId:     { from: QbTree.ownerId }
  treeName:    { kind: { type: text } }
  treeDescription: { kind: { type: text } }
  versionNo:   { from: QbTreeVersion.versionNo }
  nodeId:      { from: QbTreeNodeDef.nodeId }
  name:        { kind: { type: text } }
  description: { kind: { type: text } }
  condition:   { kind: { type: text } }
  seq:         { from: QbTreeNodeDef.seq }
  lit:         { kind: { type: bool } }
  litAt:       { from: QbTreeLit.litAt }
  unmet:       { kind: { type: text } }
  refs:        { kind: { type: text } }
  gates:       { kind: { type: text, maxLength: 400 } }
list:
  sorts: [seq]
  defaultSort: { field: seq, asc: true }
  key: [nodeId]
permissions: [qb.play]
---*/
-- What is lit now is tree.TreeLights: a lit record of the graph and node with no revocation (the views hold the
-- caller's own records only). A node is named by its graph and its id together. unmet is the node row's beforeAnswer
-- entries (<graph>/<node>) not lit, the same SQL as qb.app.publication's nodeUnlocked; refs are the nodes the condition
-- names as the version in effect resolved them (QbTreeNodeRef), each {node, tree, name, lit}; a reference resolved to
-- no graph is never lit. gates are the keys of the qb.tree.gate.* parameters that name the node (tree.TreeGates): the
-- page says what lighting it opens.
WITH g AS (
    SELECT t.{{QbTree.treeId}} AS treeId, t.{{QbTree.ownerKind}} AS ownerKind, t.{{QbTree.ownerId}} AS ownerId,
           t.{{QbTree.currentVersionId}} AS versionId
    FROM {{QbTree}} t
    WHERE CAST(t.{{QbTree.treeId}} AS text) = CAST(:treeId AS text)
      AND t.{{QbTree.status}} = 'ACTIVE' AND t.{{QbTree.subjectKind}} = 'TAKER'
      AND t.{{QbTree.ownerKind}} IN ('PLATFORM', 'SPONSOR') AND t.{{QbTree.currentVersionId}} IS NOT NULL
),
lit AS (
    SELECT l.{{QbTreeLit.treeId}} AS treeId, l.{{QbTreeLit.nodeId}} AS nodeId, min(l.{{QbTreeLit.litAt}}) AS litAt
    FROM {{QbTreeLit}} l
    WHERE NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                      WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))
    GROUP BY l.{{QbTreeLit.treeId}}, l.{{QbTreeLit.nodeId}}
),
gate AS (
    SELECT CAST(p.{{SysParam.value}} AS text) AS target,
           string_agg(p.{{SysParam.paramKey}}, ',' ORDER BY p.{{SysParam.paramKey}}) AS gates
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} LIKE 'qb.tree.gate.%'
    GROUP BY CAST(p.{{SysParam.value}} AS text)
)
SELECT CAST(g.treeId AS text) AS treeId, g.ownerKind AS ownerKind, g.ownerId AS ownerId,
       CAST(v.{{QbTreeVersion.name}} AS text) AS treeName,
       CAST(v.{{QbTreeVersion.description}} AS text) AS treeDescription,
       v.{{QbTreeVersion.versionNo}} AS versionNo,
       d.{{QbTreeNodeDef.nodeId}} AS nodeId,
       CAST(d.{{QbTreeNodeDef.name}} AS text) AS name,
       CAST(d.{{QbTreeNodeDef.description}} AS text) AS description,
       d.{{QbTreeNodeDef.condition}} AS condition,
       d.{{QbTreeNodeDef.seq}} AS seq,
       (me.nodeId IS NOT NULL) AS lit,
       me.litAt AS litAt,
       CAST((SELECT coalesce(jsonb_agg(ba.node ORDER BY ba.ord), '[]'::jsonb)
             FROM jsonb_array_elements_text(CAST(coalesce(n.{{QbTreeNode.beforeAnswer}}, '[]') AS jsonb))
                  WITH ORDINALITY AS ba(node, ord)
             WHERE NOT EXISTS (SELECT 1 FROM lit
                               WHERE lit.treeId = split_part(ba.node, '/', 1)
                                 AND lit.nodeId = split_part(ba.node, '/', 2))) AS text) AS unmet,
       CAST((SELECT coalesce(jsonb_agg(jsonb_build_object(
                     'node', r.{{QbTreeNodeRef.refNodeId}}, 'tree', r.{{QbTreeNodeRef.refTreeId}},
                     'name', (SELECT CAST(rd.{{QbTreeNodeDef.name}} AS jsonb)
                              FROM {{QbTree}} rt
                              JOIN {{QbTreeNodeDef}} rd ON rd.{{QbTreeNodeDef.versionId}} = rt.{{QbTree.currentVersionId}}
                              WHERE CAST(rt.{{QbTree.treeId}} AS text) = r.{{QbTreeNodeRef.refTreeId}}
                                AND rd.{{QbTreeNodeDef.nodeId}} = r.{{QbTreeNodeRef.refNodeId}}),
                     'lit', EXISTS (SELECT 1 FROM lit
                                    WHERE lit.treeId = r.{{QbTreeNodeRef.refTreeId}}
                                      AND lit.nodeId = r.{{QbTreeNodeRef.refNodeId}}))
                     ORDER BY r.{{QbTreeNodeRef.refNodeId}}), '[]'::jsonb)
             FROM {{QbTreeNodeRef}} r
             WHERE r.{{QbTreeNodeRef.treeId}} = CAST(g.treeId AS text)
               AND r.{{QbTreeNodeRef.nodeId}} = d.{{QbTreeNodeDef.nodeId}}) AS text) AS refs,
       gate.gates AS gates
FROM g
JOIN {{QbTreeVersion}} v ON v.{{QbTreeVersion.versionId}} = g.versionId
JOIN {{QbTreeNodeDef}} d ON d.{{QbTreeNodeDef.versionId}} = g.versionId
JOIN {{QbTreeNode}} n ON n.{{QbTreeNode.treeId}} = CAST(g.treeId AS text)
    AND n.{{QbTreeNode.nodeId}} = d.{{QbTreeNodeDef.nodeId}} AND n.{{QbTreeNode.active}}
LEFT JOIN lit me ON me.treeId = CAST(g.treeId AS text) AND me.nodeId = d.{{QbTreeNodeDef.nodeId}}
LEFT JOIN gate ON gate.target = CAST(g.treeId AS text) || '/' || d.{{QbTreeNodeDef.nodeId}}
