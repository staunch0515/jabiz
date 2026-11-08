/*---
id: qb.operator.trees
description: "F-S2b3a: the knowledge maps an operator's member looks after: the operator's copies of the platform's templates and the maps of the Sponsors the operator looks after, as qb.admin.trees gives them"
entities: [QbTree, QbTreeVersion, QbTreeNode, QbTreeLit, QbTreeLitRevocation, QbOperatorMember, QbSponsorOperator]
datasets:
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
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
permissions: [qb.operator.use]
---*/
-- Limited by the caller's own, current membership (none: no rows): the operator's copies, and its Sponsors' maps.
SELECT t.{{QbTree.treeId}} AS treeId, t.{{QbTree.ownerKind}} AS ownerKind, t.{{QbTree.ownerId}} AS ownerId,
       t.{{QbTree.subjectKind}} AS subjectKind, t.{{QbTree.status}} AS status,
       t.{{QbTree.templateTreeId}} AS templateTreeId, t.{{QbTree.revision}} AS revision,
       t.{{QbTree.currentVersionId}} AS currentVersionId,
       CAST(coalesce(eff.{{QbTreeVersion.name}}, o.{{QbTreeVersion.name}}) AS text) AS name,
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
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL AND (
    (t.{{QbTree.ownerKind}} = 'OPERATOR' AND t.{{QbTree.ownerId}} = me.{{QbOperatorMember.operatorId}})
    OR (t.{{QbTree.ownerKind}} = 'SPONSOR' AND EXISTS (
        SELECT 1 FROM {{QbSponsorOperator}} so
        WHERE so.{{QbSponsorOperator.userId}} = t.{{QbTree.ownerId}}
          AND so.{{QbSponsorOperator.operatorId}} = me.{{QbOperatorMember.operatorId}})))
