/*---
id: qb.operator.certificates
description: >-
  F-S5a: qb.admin.certificates for an operator's member: the certificates of the nodes lit on the maps the operator looks
  after (its copies, its Sponsors' maps), the holder named, with the state of the lighting; the certificates of another
  operator's maps and of the platform's maps give no rows
entities: [QbCertificate, QbTreeLitRevocation, QbTree, QbOperatorMember, QbSponsorOperator]
datasets:
  QbCertificate: urn:jabiz:dataset:default:QbCertificate
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  QbTree: urn:jabiz:dataset:default:QbTree
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
params:
  userId: { like: QbCertificate.userId, description: Only this holder's }
results:
  certificateId:    { from: QbCertificate.certificateId }
  userId:           { from: QbCertificate.userId }
  subjectKind:      { from: QbCertificate.subjectKind }
  litId:            { from: QbCertificate.litId }
  treeId:           { from: QbCertificate.treeId }
  nodeId:           { from: QbCertificate.nodeId }
  certificateNames: { kind: { type: text } }
  issuerName:       { from: QbCertificate.issuerName }
  shared:           { from: QbCertificate.shared }
  holderName:       { from: QbCertificate.holderName }
  heldFrom:         { from: QbCertificate.heldFrom }
  verifyKey:        { from: QbCertificate.verifyKey }
  issuedAt:         { from: QbCertificate.issuedAt }
  revokedAt:        { from: QbTreeLitRevocation.revokedAt }
list:
  filters: [userId, subjectKind, treeId, nodeId, litId, verifyKey, issuedAt]
  sorts:   [issuedAt]
  defaultSort: { field: issuedAt, asc: false }
  key: [certificateId]
permissions: [qb.operator.use]
---*/
-- Only a node's certificates of the maps the caller's operator looks after (the membership dataset holds the caller's own
-- rows): its copies and its Sponsors' maps, as qb.operator.tree-lit has them; the platform's maps are the platform's.
SELECT
    c.{{QbCertificate.certificateId}}        AS certificateId,
    c.{{QbCertificate.userId}}               AS userId,
    c.{{QbCertificate.subjectKind}}          AS subjectKind,
    c.{{QbCertificate.litId}}                AS litId,
    c.{{QbCertificate.treeId}}               AS treeId,
    c.{{QbCertificate.nodeId}}               AS nodeId,
    CAST(c.{{QbCertificate.certificateNames}} AS text) AS certificateNames,
    c.{{QbCertificate.issuerName}}           AS issuerName,
    c.{{QbCertificate.shared}}               AS shared,
    c.{{QbCertificate.holderName}}           AS holderName,
    c.{{QbCertificate.heldFrom}}             AS heldFrom,
    c.{{QbCertificate.verifyKey}}            AS verifyKey,
    c.{{QbCertificate.issuedAt}}             AS issuedAt,
    (SELECT max(v.{{QbTreeLitRevocation.revokedAt}}) FROM {{QbTreeLitRevocation}} v
     WHERE v.{{QbTreeLitRevocation.litId}} = c.{{QbCertificate.litId}}) AS revokedAt
FROM {{QbCertificate}} c
WHERE c.{{QbCertificate.source}} = 'NODE'
  AND (CAST(:userId AS text) IS NULL OR c.{{QbCertificate.userId}} = :userId)
  AND EXISTS (SELECT 1 FROM {{QbTree}} t
              WHERE CAST(t.{{QbTree.treeId}} AS text) = c.{{QbCertificate.treeId}}
                AND EXISTS (
                    SELECT 1 FROM {{QbOperatorMember}} me
                    WHERE me.{{QbOperatorMember.removedAt}} IS NULL AND (
                        (t.{{QbTree.ownerKind}} = 'OPERATOR' AND t.{{QbTree.ownerId}} = me.{{QbOperatorMember.operatorId}})
                        OR (t.{{QbTree.ownerKind}} = 'SPONSOR' AND EXISTS (
                            SELECT 1 FROM {{QbSponsorOperator}} so
                            WHERE so.{{QbSponsorOperator.userId}} = t.{{QbTree.ownerId}}
                              AND so.{{QbSponsorOperator.operatorId}} = me.{{QbOperatorMember.operatorId}})))))
