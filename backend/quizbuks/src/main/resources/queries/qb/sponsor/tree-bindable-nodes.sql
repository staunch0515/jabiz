/*---
id: qb.sponsor.tree-bindable-nodes
description: "F-S3: the nodes the publication wizard offers to bind a publication to: nodes in effect, asking for a pass, of a graph in effect that Takers light, which is the caller's own or (for an operator's direct Sponsor) the platform's. The same rule as publishing.PublicationNodes, which the save and the submission check again"
entities: [QbSponsor, QbOperator, QbTree, QbTreeVersion, QbTreeNode, QbTreeNodeDef]
datasets:
  QbSponsor: urn:jabiz:dataset:sponsor:QbSponsor
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeNodeDef: urn:jabiz:dataset:default:QbTreeNodeDef
results:
  treeId:      { from: QbTree.treeId }
  ownerKind:   { from: QbTree.ownerKind }
  treeName:    { kind: { type: text } }
  nodeId:      { from: QbTreeNodeDef.nodeId }
  name:        { kind: { type: text } }
  description: { kind: { type: text } }
  seq:         { from: QbTreeNodeDef.seq }
list:
  sorts: [ownerKind, seq]
  defaultSort: { field: seq, asc: true }
  key: [treeId, nodeId]
permissions: [qb.publication.write]
---*/
-- publishing.PublicationNodes#problems is the definition (TreeBindableNodesIT compares): the node row in effect and
-- bindable (its condition asks for a pass), its graph in use and lit by Takers (Sponsors' graphs cannot be bound before
-- F-S4), and the graph the caller's own (ownerKind SPONSOR, ownerId the caller) or the platform's when the caller is an
-- operator's direct Sponsor (QbOperator.directSponsorId). The caller is the sponsor: view's only onboarding row.
WITH me AS (
    SELECT s.{{QbSponsor.userId}} AS userId FROM {{QbSponsor}} s
),
direct AS (
    SELECT EXISTS (SELECT 1 FROM {{QbOperator}} o JOIN me ON o.{{QbOperator.directSponsorId}} = me.userId) AS yes
)
SELECT CAST(t.{{QbTree.treeId}} AS text) AS treeId, t.{{QbTree.ownerKind}} AS ownerKind,
       CAST(v.{{QbTreeVersion.name}} AS text) AS treeName,
       d.{{QbTreeNodeDef.nodeId}} AS nodeId,
       CAST(d.{{QbTreeNodeDef.name}} AS text) AS name, CAST(d.{{QbTreeNodeDef.description}} AS text) AS description,
       d.{{QbTreeNodeDef.seq}} AS seq
FROM {{QbTree}} t
CROSS JOIN direct
JOIN {{QbTreeVersion}} v ON v.{{QbTreeVersion.versionId}} = t.{{QbTree.currentVersionId}}
JOIN {{QbTreeNodeDef}} d ON d.{{QbTreeNodeDef.versionId}} = t.{{QbTree.currentVersionId}}
JOIN {{QbTreeNode}} n ON n.{{QbTreeNode.treeId}} = CAST(t.{{QbTree.treeId}} AS text)
    AND n.{{QbTreeNode.nodeId}} = d.{{QbTreeNodeDef.nodeId}}
WHERE t.{{QbTree.status}} = 'ACTIVE' AND t.{{QbTree.subjectKind}} = 'TAKER'
  AND n.{{QbTreeNode.active}} AND n.{{QbTreeNode.bindable}}
  AND ((t.{{QbTree.ownerKind}} = 'SPONSOR' AND t.{{QbTree.ownerId}} IN (SELECT userId FROM me))
       OR (t.{{QbTree.ownerKind}} = 'PLATFORM' AND direct.yes))
