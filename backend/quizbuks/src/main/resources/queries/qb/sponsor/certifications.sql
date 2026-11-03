/*---
id: qb.sponsor.certifications
description: The sponsor's own certifications (Q88a) with how many of their publications are rounds of each and how many takers hold each now
entities: [QbSponsorCertification, QbCertificationRound, QbAttempt]
datasets:
  QbSponsorCertification: urn:jabiz:dataset:sponsor:QbSponsorCertification
  QbCertificationRound: urn:jabiz:dataset:default:QbCertificationRound
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "Now (the client's), which the holdings are counted at" }
results:
  certificationId:      { from: QbSponsorCertification.certificationId }
  key:                  { kind: { type: text, maxLength: 40 } }
  name:                 { from: QbSponsorCertification.name }
  description:          { from: QbSponsorCertification.description }
  validityDays:         { from: QbSponsorCertification.validityDays }
  certificationVersion: { from: QbSponsorCertification.certificationVersion }
  status:               { from: QbSponsorCertification.status }
  createdAt:            { from: QbSponsorCertification.createdAt }
  updatedAt:            { from: QbSponsorCertification.updatedAt }
  rounds:               { kind: { type: numeric, precision: 9, scale: 0 } }
  openRounds:           { kind: { type: numeric, precision: 9, scale: 0 } }
  holders:              { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  key: [certificationId]
  sorts: [createdAt, updatedAt]
  defaultSort: { field: createdAt, asc: false }
permissions: [qb.publication.write]
---*/
-- The sponsor's own rows only (the dataset's scope). The rounds are the sponsor's publications registered for the
-- certification (key 'sp:<id>', D-Q88-2). Holders: the takers with a pass of a round that counts now, as
-- CertificationHolding reads it (the current version, before the round closed, within the validity); a sponsor's
-- certification has no grant or revocation by hand (D-Q88-8). A count only: who they are is the tree's business.
SELECT
    c.{{QbSponsorCertification.certificationId}}      AS certificationId,
    'sp:' || c.{{QbSponsorCertification.certificationId}} AS key,
    c.{{QbSponsorCertification.name}}                 AS name,
    c.{{QbSponsorCertification.description}}          AS description,
    c.{{QbSponsorCertification.validityDays}}         AS validityDays,
    c.{{QbSponsorCertification.certificationVersion}} AS certificationVersion,
    c.{{QbSponsorCertification.status}}               AS status,
    c.{{QbSponsorCertification.createdAt}}            AS createdAt,
    c.{{QbSponsorCertification.updatedAt}}            AS updatedAt,
    (SELECT count(*) FROM {{QbCertificationRound}} r
     WHERE r.{{QbCertificationRound.certification}} = 'sp:' || c.{{QbSponsorCertification.certificationId}}) AS rounds,
    (SELECT count(*) FROM {{QbCertificationRound}} r
     WHERE r.{{QbCertificationRound.certification}} = 'sp:' || c.{{QbSponsorCertification.certificationId}}
       AND r.{{QbCertificationRound.closedAt}} IS NULL) AS openRounds,
    (SELECT count(DISTINCT a.{{QbAttempt.userId}})
     FROM {{QbCertificationRound}} r
     JOIN {{QbAttempt}} a ON CAST(a.{{QbAttempt.publicationId}} AS text) = r.{{QbCertificationRound.publicationId}}
     WHERE r.{{QbCertificationRound.certification}} = 'sp:' || c.{{QbSponsorCertification.certificationId}}
       AND r.{{QbCertificationRound.certificationVersion}} = c.{{QbSponsorCertification.certificationVersion}}
       AND a.{{QbAttempt.passed}} AND a.{{QbAttempt.submittedAt}} IS NOT NULL
       AND (r.{{QbCertificationRound.closedAt}} IS NULL OR a.{{QbAttempt.submittedAt}} < r.{{QbCertificationRound.closedAt}})
       AND a.{{QbAttempt.submittedAt}} + make_interval(days => CAST(c.{{QbSponsorCertification.validityDays}} AS integer))
           > CAST(:at AS timestamptz)) AS holders
FROM {{QbSponsorCertification}} c
