/*---
id: qb.admin.trees
description: "F-S2b3a: every knowledge map (the platform's templates, the operators' copies, the Sponsors' maps): its owner, who lights it, its state, the version in effect and the version being edited or reviewed, how many nodes are in effect and how many subjects lit a node of it (revocations left out)"
entities: [QbTree, QbTreeVersion, QbTreeNode, QbTreeLit, QbTreeLitRevocation]
datasets:
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
results:
  treeId:            { from: QbTree.treeId }
  ownerKind:         { from: QbTree.ownerKind }
  ownerId:           { from: QbTree.ownerId }
  subjectKind:       { from: QbTree.subjectKind }
  status:            { from: QbTree.status }
  templateTreeId:    { from: QbTree.templateTreeId }
  revision:          { from: QbTree.revision }
  currentVersionId:  { from: QbTree.currentVersionId }
  name:              { kind: { type: text } }
  description:       { kind: { type: text } }
  draftName:         { kind: { type: text } }
  versionNo:         { kind: { type: numeric, precision: 6, scale: 0 } }
  openVersionId:     { kind: { type: text, maxLength: 36 } }
  openStatus:        { kind: { type: text, maxLength: 20 } }
  openVersionNo:     { kind: { type: numeric, precision: 6, scale: 0 } }
  approvalRequestId: { kind: { type: text, maxLength: 64 } }
  submittedAt:       { kind: { type: temporal, role: EVENT_TIME } }
  remindedAt:        { kind: { type: temporal, role: EVENT_TIME } }
  nodes:             { kind: { type: numeric, precision: 9, scale: 0 } }
  litSubjects:       { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [submittedAt, treeId]
  defaultSort: { field: treeId, asc: true }
  filters: [ownerKind, status, openStatus]
  key: [treeId]
permissions: [qb.admin.trees.read]
---*/
-- A map has at most one version in effect and one being edited or reviewed (partial unique indexes, V86).
SELECT t.{{QbTree.treeId}} AS treeId, t.{{QbTree.ownerKind}} AS ownerKind, t.{{QbTree.ownerId}} AS ownerId,
       t.{{QbTree.subjectKind}} AS subjectKind, t.{{QbTree.status}} AS status,
       t.{{QbTree.templateTreeId}} AS templateTreeId, t.{{QbTree.revision}} AS revision,
       t.{{QbTree.currentVersionId}} AS currentVersionId,
       CAST(coalesce(eff.{{QbTreeVersion.name}}, o.{{QbTreeVersion.name}}) AS text) AS name,
       -- F-S3e: the name and description the editor starts from (the open version's, else the one in effect).
       CAST(coalesce(o.{{QbTreeVersion.description}}, eff.{{QbTreeVersion.description}}) AS text) AS description,
       CAST(coalesce(o.{{QbTreeVersion.name}}, eff.{{QbTreeVersion.name}}) AS text) AS draftName,
       eff.{{QbTreeVersion.versionNo}} AS versionNo,
       o.{{QbTreeVersion.versionId}} AS openVersionId, o.{{QbTreeVersion.status}} AS openStatus,
       o.{{QbTreeVersion.versionNo}} AS openVersionNo, o.{{QbTreeVersion.approvalRequestId}} AS approvalRequestId,
       o.{{QbTreeVersion.submittedAt}} AS submittedAt, o.{{QbTreeVersion.remindedAt}} AS remindedAt,
       (SELECT count(*) FROM {{QbTreeNode}} n
        WHERE n.{{QbTreeNode.treeId}} = t.{{QbTree.treeId}} AND n.{{QbTreeNode.active}}) AS nodes,
       (SELECT count(DISTINCT l.{{QbTreeLit.subjectKind}} || ':' || l.{{QbTreeLit.subjectId}}) FROM {{QbTreeLit}} l
        WHERE l.{{QbTreeLit.treeId}} = t.{{QbTree.treeId}}
          AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                          WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))) AS litSubjects
FROM {{QbTree}} t
LEFT JOIN {{QbTreeVersion}} eff ON eff.{{QbTreeVersion.versionId}} = t.{{QbTree.currentVersionId}}
LEFT JOIN {{QbTreeVersion}} o ON o.{{QbTreeVersion.treeId}} = t.{{QbTree.treeId}}
    AND o.{{QbTreeVersion.status}} IN ('DRAFT', 'REVIEW', 'REJECTED')
