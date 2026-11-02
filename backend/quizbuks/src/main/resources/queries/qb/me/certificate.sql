/*---
id: qb.me.certificate
description: >-
  The caller's own certificate as its PDF shows it (Q77, the document qb.certificate): the number is the verify key,
  the certification's name and the verify address are given by the issue (the name in the certificate's language)
entities: [QbCertificate]
datasets:
  QbCertificate: urn:jabiz:dataset:me:QbCertificate
params:
  certificateId:     { like: QbCertificate.certificateId, required: true, description: The certificate }
  certificationName: { kind: { type: text, maxLength: 200 }, required: true, description: "The certification's name in the certificate's language" }
  verifyAddress:     { kind: { type: text, maxLength: 300 }, required: true, description: The page that verifies it }
results:
  certificateNo:     { from: QbCertificate.verifyKey }
  holderName:        { from: QbCertificate.holderName }
  certificationName: { kind: { type: text, maxLength: 200 } }
  heldFrom:          { from: QbCertificate.heldFrom }
  validUntil:        { from: QbCertificate.validUntil }
  verifyAddress:     { kind: { type: text, maxLength: 300 } }
permissions: [qb.me]
---*/
-- The dataset is the caller's own rows: another user's certificate id finds nothing.
SELECT
    c.{{QbCertificate.verifyKey}}  AS certificateNo,
    c.{{QbCertificate.holderName}} AS holderName,
    CAST(:certificationName AS varchar(200)) AS certificationName,
    c.{{QbCertificate.heldFrom}}   AS heldFrom,
    c.{{QbCertificate.validUntil}} AS validUntil,
    CAST(:verifyAddress AS varchar(300)) AS verifyAddress
FROM {{QbCertificate}} c
WHERE c.{{QbCertificate.certificateId}} = :certificateId
