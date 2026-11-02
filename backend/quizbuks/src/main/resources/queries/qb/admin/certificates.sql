/*---
id: qb.admin.certificates
description: >-
  The certificates people issued, newest first (Q77): whose, which certification, the holding, the name on it and the
  key (support checks one a verifier asks about); a user's for their page
entities: [QbCertificate]
datasets:
  QbCertificate: urn:jabiz:dataset:default:QbCertificate
params:
  userId: { like: QbCertificate.userId, description: Only this user's }
results:
  certificateId:        { from: QbCertificate.certificateId }
  userId:               { from: QbCertificate.userId }
  certification:        { from: QbCertificate.certification }
  certificationVersion: { from: QbCertificate.certificationVersion }
  holderName:           { from: QbCertificate.holderName }
  heldFrom:             { from: QbCertificate.heldFrom }
  validUntil:           { from: QbCertificate.validUntil }
  verifyKey:            { from: QbCertificate.verifyKey }
  language:             { from: QbCertificate.language }
  issuedAt:             { from: QbCertificate.issuedAt }
list:
  filters: [userId, certification, verifyKey, issuedAt]
  sorts:   [issuedAt, validUntil]
  defaultSort: { field: issuedAt, asc: false }
  key: [certificateId]
permissions: [qb.admin.users.read]
---*/
SELECT
    c.{{QbCertificate.certificateId}}        AS certificateId,
    c.{{QbCertificate.userId}}               AS userId,
    c.{{QbCertificate.certification}}        AS certification,
    c.{{QbCertificate.certificationVersion}} AS certificationVersion,
    c.{{QbCertificate.holderName}}           AS holderName,
    c.{{QbCertificate.heldFrom}}             AS heldFrom,
    c.{{QbCertificate.validUntil}}           AS validUntil,
    c.{{QbCertificate.verifyKey}}            AS verifyKey,
    c.{{QbCertificate.language}}             AS language,
    c.{{QbCertificate.issuedAt}}             AS issuedAt
FROM {{QbCertificate}} c
WHERE (CAST(:userId AS text) IS NULL OR c.{{QbCertificate.userId}} = :userId)
