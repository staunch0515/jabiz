/*---
id: qb.sponsor.package-tree-nodes
description: >-
  F-C4: the nodes of one version of one of the caller's knowledge maps, in their order, with their conditions (a
  sponsor package's export; the nodes in effect a package's publications are bound to by node key)
entities: [QbTree, QbTreeVersion, QbTreeNodeDef]
datasets:
  QbTree: urn:jabiz:dataset:sponsor:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNodeDef: urn:jabiz:dataset:default:QbTreeNodeDef
params:
  versionId: { like: QbTreeVersion.versionId, required: true, description: The version }
results:
  nodeId:      { from: QbTreeNodeDef.nodeId }
  name:        { kind: { type: text } }
  description: { kind: { type: text } }
  condition:   { kind: { type: text } }
  seq:         { from: QbTreeNodeDef.seq }
list:
  key: [nodeId]
  sorts: [seq]
  defaultSort: { field: seq, asc: true }
permissions: [qb.sponsor.me]
---*/
-- Only a version of the caller's own map (another's gives no rows).
SELECT d.{{QbTreeNodeDef.nodeId}} AS nodeId,
       CAST(d.{{QbTreeNodeDef.name}} AS text) AS name,
       CAST(d.{{QbTreeNodeDef.description}} AS text) AS description,
       d.{{QbTreeNodeDef.condition}} AS condition,
       d.{{QbTreeNodeDef.seq}} AS seq
FROM {{QbTreeVersion}} v
JOIN {{QbTree}} t ON t.{{QbTree.treeId}} = v.{{QbTreeVersion.treeId}}
JOIN {{QbTreeNodeDef}} d ON d.{{QbTreeNodeDef.versionId}} = v.{{QbTreeVersion.versionId}}
WHERE v.{{QbTreeVersion.versionId}} = CAST(:versionId AS text)
