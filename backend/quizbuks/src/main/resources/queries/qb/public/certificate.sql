/*---
id: qb.public.certificate
description: >-
  A certificate as its verify page (/taker/verify/<key>) shows it to anybody who has its key (Q77, D-Q77-6; F-S5a): the
  holder's name when issued, what the certificate is (a lit node's, with its names and issuer, or Q77's certification with
  its holding) and its state - the latest revocation (of the lighting a node's came from; of the certification after the
  holding began, Q77's); none for an unknown key. A node's certificate has no end; Q77's validity the page reads from
  validUntil. A certificate its holder stopped sharing shows nothing but that: every other column is empty.
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [QbCertificate, QbCertificationRecord, QbTreeLitRevocation, QbSponsorCertification, QbSponsor]
datasets:
  QbCertificate: urn:jabiz:dataset:public:QbCertificate
  QbCertificationRecord: urn:jabiz:dataset:public:QbCertificationRecord
  QbTreeLitRevocation: urn:jabiz:dataset:public:QbTreeLitRevocation
  QbSponsorCertification: urn:jabiz:dataset:public:QbSponsorCertification
  QbSponsor: urn:jabiz:dataset:public:QbSponsor
params:
  key: { like: QbCertificate.verifyKey, required: true, description: "The certificate's key (32 hex digits, lowercase)" }
results:
  source:               { from: QbCertificate.source }
  shared:               { from: QbCertificate.shared }
  certification:        { from: QbCertificate.certification }
  certificationVersion: { from: QbCertificate.certificationVersion }
  certificationNames:   { kind: { type: text } }
  certificateNames:     { kind: { type: text } }
  issuerName:           { kind: { type: text, maxLength: 200 } }
  holderName:           { from: QbCertificate.holderName }
  heldFrom:             { from: QbCertificate.heldFrom }
  validUntil:           { from: QbCertificate.validUntil }
  issuedAt:             { from: QbCertificate.issuedAt }
  revokedAt:            { kind: { type: temporal, role: EVENT_TIME } }
list:
  key: [source]
---*/
-- The record datasets hold revocations only. Q77's: one after the holding began ends it. A node's: the revocation of the
-- lit record the certificate came from. A certificate not shared gives nothing of the holder, the names or the state.
SELECT
    c.{{QbCertificate.source}}               AS source,
    c.{{QbCertificate.shared}}               AS shared,
    CASE WHEN c.{{QbCertificate.shared}} THEN c.{{QbCertificate.certification}} END AS certification,
    CASE WHEN c.{{QbCertificate.shared}} THEN c.{{QbCertificate.certificationVersion}} END AS certificationVersion,
    -- Q88a (D-Q88-6): a sponsor's certification is named by its row and issued by the sponsor; the catalog's
    -- names are the App's texts and their issuer the platform.
    CASE WHEN c.{{QbCertificate.shared}} THEN CAST(sc.{{QbSponsorCertification.name}} AS text) END AS certificationNames,
    CASE WHEN c.{{QbCertificate.shared}} THEN CAST(c.{{QbCertificate.certificateNames}} AS text) END AS certificateNames,
    CASE WHEN c.{{QbCertificate.shared}} THEN CAST(coalesce(c.{{QbCertificate.issuerName}}, sp.{{QbSponsor.company}}) AS varchar(200)) END AS issuerName,
    CASE WHEN c.{{QbCertificate.shared}} THEN c.{{QbCertificate.holderName}} END AS holderName,
    CASE WHEN c.{{QbCertificate.shared}} THEN c.{{QbCertificate.heldFrom}} END AS heldFrom,
    CASE WHEN c.{{QbCertificate.shared}} THEN c.{{QbCertificate.validUntil}} END AS validUntil,
    CASE WHEN c.{{QbCertificate.shared}} THEN c.{{QbCertificate.issuedAt}} END AS issuedAt,
    CASE WHEN NOT c.{{QbCertificate.shared}} THEN NULL
         WHEN c.{{QbCertificate.source}} = 'NODE' THEN
             (SELECT max(v.{{QbTreeLitRevocation.revokedAt}}) FROM {{QbTreeLitRevocation}} v
              WHERE v.{{QbTreeLitRevocation.litId}} = c.{{QbCertificate.litId}})
         ELSE
             (SELECT max(r.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} r
              WHERE r.{{QbCertificationRecord.userId}} = c.{{QbCertificate.userId}}
                AND r.{{QbCertificationRecord.certification}} = c.{{QbCertificate.certification}}
                AND r.{{QbCertificationRecord.at}} > c.{{QbCertificate.heldFrom}})
    END AS revokedAt
FROM {{QbCertificate}} c
LEFT JOIN {{QbSponsorCertification}} sc
    ON 'sp:' || sc.{{QbSponsorCertification.certificationId}} = c.{{QbCertificate.certification}}
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = sc.{{QbSponsorCertification.sponsorId}}
WHERE c.{{QbCertificate.verifyKey}} = :key
