/*---
id: qb.sponsor.benefit-access
description: >-
  Q89a (D-Q89-1): until when the caller holds each of the two benefit certifications (none: not held), and the
  publication of each one's current round (none: no round open now), for the pages that need one
entities: [QbCertificationRound, QbCertificationRecord, QbPublicationCard, QbAttempt, QbSponsorCertification]
datasets:
  QbCertificationRound: urn:jabiz:dataset:default:QbCertificationRound
  QbSponsorCertification: urn:jabiz:dataset:default:QbSponsorCertification
  QbCertificationRecord: urn:jabiz:dataset:me:QbCertificationRecord
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time asked about (the client's now) }
results:
  certification:      { kind: { type: text, maxLength: 40 } }
  heldUntil:          { kind: { type: temporal, role: EVENT_TIME } }
  roundPublicationId: { kind: { type: text, maxLength: 36 } }
list:
  key: [certification]
  sorts: [certification]
  defaultSort: { field: certification, asc: true }
permissions: [qb.benefit.use]
---*/
-- One row per benefit certification (the catalog as Certifications writes it, CertificationTemplatesTest). The holding
-- is CertificationHolding's rule over the caller's records and their passes not recorded yet (as qb.app.understanding);
-- the processes decide with RoundHolders, the same rule: this only tells the pages what to show.
SELECT
    cat.cert AS certification,
    (SELECT max(e.until)
     FROM (SELECT rec.{{QbCertificationRecord.certificationVersion}} AS ver, rec.{{QbCertificationRecord.at}} AS at,
                  rec.{{QbCertificationRecord.validUntil}} AS until
           FROM {{QbCertificationRecord}} rec
           WHERE rec.{{QbCertificationRecord.certification}} = cat.cert
             AND rec.{{QbCertificationRecord.kind}} IN ('PASSED', 'GRANTED')
           UNION ALL
           SELECT r2.{{QbCertificationRound.certificationVersion}}, a.{{QbAttempt.submittedAt}},
                  a.{{QbAttempt.submittedAt}} + make_interval(secs => cat.secs)
           FROM {{QbCertificationRound}} r2
           JOIN {{QbAttempt}} a ON CAST(a.{{QbAttempt.publicationId}} AS text) = r2.{{QbCertificationRound.publicationId}}
           WHERE r2.{{QbCertificationRound.certification}} = cat.cert
             AND a.{{QbAttempt.passed}} AND a.{{QbAttempt.submittedAt}} IS NOT NULL
             AND (r2.{{QbCertificationRound.closedAt}} IS NULL
                  OR a.{{QbAttempt.submittedAt}} < r2.{{QbCertificationRound.closedAt}})) e
     WHERE e.ver = cat.ver AND e.until > CAST(:at AS timestamptz)
       AND e.at > coalesce((SELECT max(rv.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} rv
                            WHERE rv.{{QbCertificationRecord.certification}} = cat.cert
                              AND rv.{{QbCertificationRecord.kind}} = 'REVOKED'), '-infinity')) AS heldUntil,
    (SELECT max(cr.{{QbCertificationRound.publicationId}})
     FROM {{QbCertificationRound}} cr
     JOIN {{QbPublicationCard}} rp ON CAST(rp.{{QbPublicationCard.publicationId}} AS text) = cr.{{QbCertificationRound.publicationId}}
     WHERE cr.{{QbCertificationRound.certification}} = cat.cert AND cr.{{QbCertificationRound.closedAt}} IS NULL
       AND rp.{{QbPublicationCard.status}} = 'LIVE' AND rp.{{QbPublicationCard.startAt}} <= CAST(:at AS timestamptz)
       AND rp.{{QbPublicationCard.endAt}} > CAST(:at AS timestamptz)) AS roundPublicationId
FROM (SELECT * FROM (VALUES ('SPONSOR_APPLY', 1, 31536000), ('REFERRER', 1, 31536000), ('TERMS_APP_UNDERSTOOD', 1, 31536000), ('CONTEST_RULES_UNDERSTOOD', 1, 31536000), ('TERMS_SPONSOR_UNDERSTOOD', 1, 31536000), ('BENEFIT_PROVIDER', 1, 31536000), ('BENEFIT_SPONSOR', 1, 31536000)) AS v(cert, ver, secs) UNION ALL SELECT 'sp:' || sc.{{QbSponsorCertification.certificationId}}, sc.{{QbSponsorCertification.certificationVersion}}, sc.{{QbSponsorCertification.validityDays}} * 86400 FROM {{QbSponsorCertification}} sc) AS cat(cert, ver, secs)
WHERE cat.cert IN ('BENEFIT_PROVIDER', 'BENEFIT_SPONSOR')
