/*---
id: qb.system.tree-count-items
description: "F-S3f: the graphs in effect (F-S4c: those Takers light and those Sponsors light): the items of the daily job qb.tree-node-counts, which counts, node by node, the people who answered and have not lit"
entities: [QbTree]
datasets:
  QbTree: urn:jabiz:dataset:default:QbTree
results:
  treeId: { from: QbTree.treeId }
list:
  sorts: [treeId]
  defaultSort: { field: treeId, asc: true }
  key: [treeId]
permissions: [qb.tree.system]
---*/
SELECT CAST(t.{{QbTree.treeId}} AS text) AS treeId
FROM {{QbTree}} t
-- F-S4c: maps Sponsors light too (their copies' nodes show the counts to the operator).
WHERE t.{{QbTree.status}} = 'ACTIVE' AND t.{{QbTree.subjectKind}} IN ('TAKER', 'SPONSOR')
  AND t.{{QbTree.currentVersionId}} IS NOT NULL
