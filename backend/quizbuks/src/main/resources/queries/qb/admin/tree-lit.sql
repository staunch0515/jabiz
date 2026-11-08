/*---
id: qb.admin.tree-lit
description: "F-S2b3a: the nodes lit, by subject, map or node: when and why each was lit, whether it was revoked (when and why), and the request to revoke it that waits for review"
entities: [QbTreeLit, QbTreeLitRevocation, QbTreeLitRevokeRequest]
datasets:
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  QbTreeLitRevokeRequest: urn:jabiz:dataset:default:QbTreeLitRevokeRequest
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
permissions: [qb.admin.trees.read]
---*/
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
