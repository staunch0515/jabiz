/*---
id: qb.sponsor.node-certificate
description: >-
  The caller's own certificate of a node lit as a Sponsor as its PDF shows it (F-S5a, the document qb.node_certificate_sponsor): the number
  is the verify key; the holder, the issuer and the time lit are as frozen when lit, the name in the document's language
  and the verify address are given by the issue. There is no end to show.
entities: [QbCertificate]
datasets:
  QbCertificate: urn:jabiz:dataset:me-sponsor:QbCertificate
params:
  certificateId:   { like: QbCertificate.certificateId, required: true, description: The certificate }
  certificateName: { kind: { type: text, maxLength: 200 }, required: true, description: "The certificate's name in the document's language" }
  verifyAddress:   { kind: { type: text, maxLength: 300 }, required: true, description: The page that verifies it }
results:
  certificateNo:   { from: QbCertificate.verifyKey }
  issuerName:      { from: QbCertificate.issuerName }
  holderName:      { from: QbCertificate.holderName }
  certificateName: { kind: { type: text, maxLength: 200 } }
  litAt:           { from: QbCertificate.heldFrom }
  verifyAddress:   { kind: { type: text, maxLength: 300 } }
permissions: [qb.sponsor.me]
---*/
-- The dataset is the caller's own rows as a Sponsor: another person's certificate id finds nothing.
SELECT
    c.{{QbCertificate.verifyKey}}   AS certificateNo,
    c.{{QbCertificate.issuerName}}  AS issuerName,
    c.{{QbCertificate.holderName}}  AS holderName,
    CAST(:certificateName AS varchar(200)) AS certificateName,
    c.{{QbCertificate.heldFrom}}    AS litAt,
    CAST(:verifyAddress AS varchar(300)) AS verifyAddress
FROM {{QbCertificate}} c
WHERE c.{{QbCertificate.certificateId}} = :certificateId
  AND c.{{QbCertificate.source}} = 'NODE'
