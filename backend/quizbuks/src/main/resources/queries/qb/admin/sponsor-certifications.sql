/*---
id: qb.admin.sponsor-certifications
description: The sponsors' own certifications (Q88a) as headquarters and the operators see them - whose, how many rounds and holders; an operator sees its sponsors' only
entities: [QbSponsorCertification, QbSponsor, QbCertificationRound, QbAttempt, QbSponsorOperator, QbOperator]
datasets:
  QbSponsorCertification: urn:jabiz:dataset:default:QbSponsorCertification
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbCertificationRound: urn:jabiz:dataset:default:QbCertificationRound
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperator: urn:jabiz:dataset:default:QbOperator
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "Now (the client's), which the holdings are counted at" }
  q:  { kind: { type: text, maxLength: 100 }, description: Only certifications of sponsors whose company name contains this text }
results:
  certificationId:      { from: QbSponsorCertification.certificationId }
  key:                  { kind: { type: text, maxLength: 40 } }
  sponsorId:            { from: QbSponsorCertification.sponsorId }
  company:              { from: QbSponsor.company }
  name:                 { from: QbSponsorCertification.name }
  validityDays:         { from: QbSponsorCertification.validityDays }
  certificationVersion: { from: QbSponsorCertification.certificationVersion }
  status:               { from: QbSponsorCertification.status }
  createdAt:            { from: QbSponsorCertification.createdAt }
  updatedAt:            { from: QbSponsorCertification.updatedAt }
  rounds:               { kind: { type: numeric, precision: 9, scale: 0 } }
  holders:              { kind: { type: numeric, precision: 9, scale: 0 } }
  # F-S1b: the sponsor's operator now (every sponsor has one), for the platform's filter by operator.
  operatorId:   { from: QbSponsorOperator.operatorId }
  operatorName: { from: QbOperator.name }
list:
  filters: [operatorId]
  key: [certificationId]
  sorts: [createdAt, updatedAt, company]
  defaultSort: { field: createdAt, asc: false }
permissions: [qb.admin.publication.read]
---*/
-- Read only (D-Q88-8): an operator closes a sponsor's round with QB_CERTIFICATION_ROUND_CLOSE as its own. The
-- operators' scope narrows the rows to their sponsors' (OperatorScopes, by sponsorId). Holders as
-- qb.sponsor.certifications counts them.
SELECT
    c.{{QbSponsorCertification.certificationId}}      AS certificationId,
    'sp:' || c.{{QbSponsorCertification.certificationId}} AS key,
    c.{{QbSponsorCertification.sponsorId}}            AS sponsorId,
    sp.{{QbSponsor.company}}                          AS company,
    c.{{QbSponsorCertification.name}}                 AS name,
    c.{{QbSponsorCertification.validityDays}}         AS validityDays,
    c.{{QbSponsorCertification.certificationVersion}} AS certificationVersion,
    c.{{QbSponsorCertification.status}}               AS status,
    c.{{QbSponsorCertification.createdAt}}            AS createdAt,
    c.{{QbSponsorCertification.updatedAt}}            AS updatedAt,
    (SELECT count(*) FROM {{QbCertificationRound}} r
     WHERE r.{{QbCertificationRound.certification}} = 'sp:' || c.{{QbSponsorCertification.certificationId}}) AS rounds,
    (SELECT count(DISTINCT a.{{QbAttempt.userId}})
     FROM {{QbCertificationRound}} r
     JOIN {{QbAttempt}} a ON CAST(a.{{QbAttempt.publicationId}} AS text) = r.{{QbCertificationRound.publicationId}}
     WHERE r.{{QbCertificationRound.certification}} = 'sp:' || c.{{QbSponsorCertification.certificationId}}
       AND r.{{QbCertificationRound.certificationVersion}} = c.{{QbSponsorCertification.certificationVersion}}
       AND a.{{QbAttempt.passed}} AND a.{{QbAttempt.submittedAt}} IS NOT NULL
       AND (r.{{QbCertificationRound.closedAt}} IS NULL OR a.{{QbAttempt.submittedAt}} < r.{{QbCertificationRound.closedAt}})
       AND a.{{QbAttempt.submittedAt}} + make_interval(days => CAST(c.{{QbSponsorCertification.validityDays}} AS integer))
           > CAST(:at AS timestamptz)) AS holders,
    qso.{{QbSponsorOperator.operatorId}} AS operatorId,
    qop.{{QbOperator.name}}               AS operatorName
FROM {{QbSponsorCertification}} c
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = c.{{QbSponsorCertification.sponsorId}}
LEFT JOIN {{QbSponsorOperator}} qso ON qso.{{QbSponsorOperator.userId}} = c.{{QbSponsorCertification.sponsorId}}
LEFT JOIN {{QbOperator}} qop ON qop.{{QbOperator.operatorId}} = qso.{{QbSponsorOperator.operatorId}}
WHERE CAST(:q AS text) IS NULL OR strpos(lower(coalesce(sp.{{QbSponsor.company}}, '')), lower(CAST(:q AS text))) > 0
