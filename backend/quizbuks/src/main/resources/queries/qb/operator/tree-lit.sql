/*---
id: qb.operator.tree-lit
description: "F-S2b3a: qb.admin.tree-lit for an operator's member: the nodes lit on the maps the operator looks after (its copies, its Sponsors' maps), with their revocations and the requests in review; another operator's map gives no rows"
entities: [QbTreeLit, QbTreeLitRevocation, QbTreeLitRevokeRequest, QbTree, QbOperatorMember, QbSponsorOperator]
datasets:
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  QbTreeLitRevokeRequest: urn:jabiz:dataset:default:QbTreeLitRevokeRequest
  QbTree: urn:jabiz:dataset:default:QbTree
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
params:
  subjectId: { like: QbTreeLit.subjectId, description: Only this subject's }
  treeId:    { like: QbTreeLit.treeId, description: Only this map's }
  nodeId:    { like: QbTreeLit.nodeId, description: Only this node's }
results:
  litId:             { from: QbTreeLit.litId }
  subjectKind:       { from: QbTreeLit.subjectKind }
  subjectId:         { from: QbTreeLit.subjectId }
  treeId:            { from: QbTreeLit.treeId }
  nodeId:            { from: QbTreeLit.nodeId }
  litAt:             { from: QbTreeLit.litAt }
  basis:             { from: QbTreeLit.basis }
  revokedAt:         { from: QbTreeLitRevocation.revokedAt }
  revokeReason:      { from: QbTreeLitRevocation.reason }
  requestId:         { from: QbTreeLitRevokeRequest.requestId }
  requestStatus:     { from: QbTreeLitRevokeRequest.status }
  approvalRequestId: { from: QbTreeLitRevokeRequest.approvalRequestId }
  requestedBy:       { from: QbTreeLitRevokeRequest.requestedBy }
list:
  sorts: [litAt]
  defaultSort: { field: litAt, asc: false }
  filters: [subjectKind]
  key: [litId]
permissions: [qb.operator.use]
---*/
-- Only the maps the caller's operator looks after (the membership dataset holds the caller's own rows).
-- At least one of the three is given in practice (the review page gives the map); none lists every record.
SELECT l.{{QbTreeLit.litId}} AS litId, l.{{QbTreeLit.subjectKind}} AS subjectKind, l.{{QbTreeLit.subjectId}} AS subjectId,
       l.{{QbTreeLit.treeId}} AS treeId, l.{{QbTreeLit.nodeId}} AS nodeId, l.{{QbTreeLit.litAt}} AS litAt,
       l.{{QbTreeLit.basis}} AS basis, r.{{QbTreeLitRevocation.revokedAt}} AS revokedAt,
       r.{{QbTreeLitRevocation.reason}} AS revokeReason, q.{{QbTreeLitRevokeRequest.requestId}} AS requestId,
       q.{{QbTreeLitRevokeRequest.status}} AS requestStatus,
       q.{{QbTreeLitRevokeRequest.approvalRequestId}} AS approvalRequestId,
       q.{{QbTreeLitRevokeRequest.requestedBy}} AS requestedBy
FROM {{QbTreeLit}} l
LEFT JOIN {{QbTreeLitRevocation}} r ON r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text)
LEFT JOIN {{QbTreeLitRevokeRequest}} q ON q.{{QbTreeLitRevokeRequest.litId}} = CAST(l.{{QbTreeLit.litId}} AS text)
    AND q.{{QbTreeLitRevokeRequest.status}} = 'REVIEW'
WHERE (CAST(:subjectId AS text) IS NULL OR l.{{QbTreeLit.subjectId}} = CAST(:subjectId AS text))
  AND (CAST(:treeId AS text) IS NULL OR l.{{QbTreeLit.treeId}} = CAST(:treeId AS text))
  AND (CAST(:nodeId AS text) IS NULL OR l.{{QbTreeLit.nodeId}} = CAST(:nodeId AS text))
  AND EXISTS (SELECT 1 FROM {{QbTree}} t
              WHERE t.{{QbTree.treeId}} = l.{{QbTreeLit.treeId}}
                AND EXISTS (
                    SELECT 1 FROM {{QbOperatorMember}} me
                    WHERE me.{{QbOperatorMember.removedAt}} IS NULL AND (
                        (t.{{QbTree.ownerKind}} = 'OPERATOR' AND t.{{QbTree.ownerId}} = me.{{QbOperatorMember.operatorId}})
                        OR (t.{{QbTree.ownerKind}} = 'SPONSOR' AND EXISTS (
                            SELECT 1 FROM {{QbSponsorOperator}} so
                            WHERE so.{{QbSponsorOperator.userId}} = t.{{QbTree.ownerId}}
                              AND so.{{QbSponsorOperator.operatorId}} = me.{{QbOperatorMember.operatorId}})))))
