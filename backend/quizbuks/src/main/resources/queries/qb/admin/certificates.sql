/*---
id: qb.admin.certificates
description: >-
  The certificates people hold, newest first (Q77; F-S5a): whose, of which source - a lit node's (with the map, the node, the
  names, the issuer, whether its verify page is shared and when the lighting was revoked) or Q77's certification with its
  holding - the name on it and the key (support checks one a verifier asks about); a user's for their page
entities: [QbCertificate, QbTreeLitRevocation]
datasets:
  QbCertificate: urn:jabiz:dataset:default:QbCertificate
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
params:
  userId: { like: QbCertificate.userId, description: Only this user's }
results:
  certificateId:        { from: QbCertificate.certificateId }
  userId:               { from: QbCertificate.userId }
  source:               { from: QbCertificate.source }
  subjectKind:          { from: QbCertificate.subjectKind }
  certification:        { from: QbCertificate.certification }
  certificationVersion: { from: QbCertificate.certificationVersion }
  litId:                { from: QbCertificate.litId }
  treeId:               { from: QbCertificate.treeId }
  nodeId:               { from: QbCertificate.nodeId }
  certificateNames:     { kind: { type: text } }
  issuerName:           { from: QbCertificate.issuerName }
  shared:               { from: QbCertificate.shared }
  holderName:           { from: QbCertificate.holderName }
  heldFrom:             { from: QbCertificate.heldFrom }
  validUntil:           { from: QbCertificate.validUntil }
  verifyKey:            { from: QbCertificate.verifyKey }
  language:             { from: QbCertificate.language }
  issuedAt:             { from: QbCertificate.issuedAt }
  revokedAt:            { from: QbTreeLitRevocation.revokedAt }
list:
  filters: [userId, source, subjectKind, certification, treeId, nodeId, litId, verifyKey, issuedAt]
  sorts:   [issuedAt, validUntil]
  defaultSort: { field: issuedAt, asc: false }
  key: [certificateId]
permissions: [qb.admin.users.read]
---*/
SELECT
    c.{{QbCertificate.certificateId}}        AS certificateId,
    c.{{QbCertificate.userId}}               AS userId,
    c.{{QbCertificate.source}}               AS source,
    c.{{QbCertificate.subjectKind}}          AS subjectKind,
    c.{{QbCertificate.certification}}        AS certification,
    c.{{QbCertificate.certificationVersion}} AS certificationVersion,
    c.{{QbCertificate.litId}}                AS litId,
    c.{{QbCertificate.treeId}}               AS treeId,
    c.{{QbCertificate.nodeId}}               AS nodeId,
    CAST(c.{{QbCertificate.certificateNames}} AS text) AS certificateNames,
    c.{{QbCertificate.issuerName}}           AS issuerName,
    c.{{QbCertificate.shared}}               AS shared,
    c.{{QbCertificate.holderName}}           AS holderName,
    c.{{QbCertificate.heldFrom}}             AS heldFrom,
    c.{{QbCertificate.validUntil}}           AS validUntil,
    c.{{QbCertificate.verifyKey}}            AS verifyKey,
    c.{{QbCertificate.language}}             AS language,
    c.{{QbCertificate.issuedAt}}             AS issuedAt,
    (SELECT max(v.{{QbTreeLitRevocation.revokedAt}}) FROM {{QbTreeLitRevocation}} v
     WHERE v.{{QbTreeLitRevocation.litId}} = c.{{QbCertificate.litId}}) AS revokedAt
FROM {{QbCertificate}} c
WHERE (CAST(:userId AS text) IS NULL OR c.{{QbCertificate.userId}} = :userId)
