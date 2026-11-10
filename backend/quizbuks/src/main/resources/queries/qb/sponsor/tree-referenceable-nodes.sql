/*---
id: qb.sponsor.tree-referenceable-nodes
description: "F-S3e: the nodes of other graphs a Sponsor's own graph may name in its conditions: every node of the version in effect of the platform's graphs in effect that Takers light. The same graphs as the draft save's rules (tree.TreeProcesses loadRules for a Sponsor's graph), which check again"
entities: [QbSponsor, QbTree, QbTreeVersion, QbTreeNodeDef]
datasets:
  QbSponsor: urn:jabiz:dataset:sponsor:QbSponsor
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNodeDef: urn:jabiz:dataset:default:QbTreeNodeDef
results:
  treeId:      { from: QbTree.treeId }
  treeName:    { kind: { type: text } }
  nodeId:      { from: QbTreeNodeDef.nodeId }
  name:        { kind: { type: text } }
  seq:         { from: QbTreeNodeDef.seq }
list:
  sorts: [treeId, seq]
  defaultSort: { field: seq, asc: true }
  key: [treeId, nodeId]
permissions: [qb.sponsor.me]
---*/
-- The rules a Sponsor's draft is saved with take the platform's graphs in effect lit by Takers as the others it may
-- name (TreeProcesses: ownerKind PLATFORM, subjectKind TAKER, status ACTIVE, the nodes of the version in effect); this
-- lists the same, for the editor to offer. Only a Sponsor (the sponsor: view's onboarding row) gets rows.
SELECT CAST(t.{{QbTree.treeId}} AS text) AS treeId,
       CAST(v.{{QbTreeVersion.name}} AS text) AS treeName,
       d.{{QbTreeNodeDef.nodeId}} AS nodeId,
       CAST(d.{{QbTreeNodeDef.name}} AS text) AS name,
       d.{{QbTreeNodeDef.seq}} AS seq
FROM {{QbTree}} t
JOIN {{QbTreeVersion}} v ON v.{{QbTreeVersion.versionId}} = t.{{QbTree.currentVersionId}}
JOIN {{QbTreeNodeDef}} d ON d.{{QbTreeNodeDef.versionId}} = t.{{QbTree.currentVersionId}}
WHERE t.{{QbTree.status}} = 'ACTIVE' AND t.{{QbTree.ownerKind}} = 'PLATFORM' AND t.{{QbTree.subjectKind}} = 'TAKER'
  AND EXISTS (SELECT 1 FROM {{QbSponsor}} s)
