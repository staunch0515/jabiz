/*---
id: qb.public.certificate
description: >-
  A certificate as its verify page (/taker/verify/<key>) shows it to anybody who has its key (Q77, D-Q77-6): the
  holder's name when issued, the certification, the holding and the latest revocation of it after the holding began;
  none for an unknown key. Whether it is still valid the page reads from validUntil.
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [QbCertificate, QbCertificationRecord]
datasets:
  QbCertificate: urn:jabiz:dataset:public:QbCertificate
  QbCertificationRecord: urn:jabiz:dataset:public:QbCertificationRecord
params:
  key: { like: QbCertificate.verifyKey, required: true, description: "The certificate's key (32 hex digits, lowercase)" }
results:
  certification:        { from: QbCertificate.certification }
  certificationVersion: { from: QbCertificate.certificationVersion }
  holderName:           { from: QbCertificate.holderName }
  heldFrom:             { from: QbCertificate.heldFrom }
  validUntil:           { from: QbCertificate.validUntil }
  issuedAt:             { from: QbCertificate.issuedAt }
  revokedAt:            { from: QbCertificationRecord.at }
list:
  key: [certification]
---*/
-- The record dataset holds revocations only (its fixed scope); one after the holding began ends it.
SELECT
    c.{{QbCertificate.certification}}        AS certification,
    c.{{QbCertificate.certificationVersion}} AS certificationVersion,
    c.{{QbCertificate.holderName}}           AS holderName,
    c.{{QbCertificate.heldFrom}}             AS heldFrom,
    c.{{QbCertificate.validUntil}}           AS validUntil,
    c.{{QbCertificate.issuedAt}}             AS issuedAt,
    (SELECT max(r.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} r
     WHERE r.{{QbCertificationRecord.userId}} = c.{{QbCertificate.userId}}
       AND r.{{QbCertificationRecord.certification}} = c.{{QbCertificate.certification}}
       AND r.{{QbCertificationRecord.at}} > c.{{QbCertificate.heldFrom}}) AS revokedAt
FROM {{QbCertificate}} c
WHERE c.{{QbCertificate.verifyKey}} = :key
