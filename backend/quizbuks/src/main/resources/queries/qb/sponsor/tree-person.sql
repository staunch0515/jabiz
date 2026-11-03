/*---
id: qb.sponsor.tree-person
description: >-
  One customer's states on every node of the sponsor's tree (Q85, D-Q85-4), the customer named by the key the
  transactions page filters by; no rows for a key that is nobody's
entities: [QbPublication, QbPublicationPrerequisite, QbAttempt, QbProfile, QbCertificationRound, QbCertificationRecord, QbSponsorCertification]
datasets:
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbPublicationPrerequisite: urn:jabiz:dataset:sponsor:QbPublicationPrerequisite
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbProfile: urn:jabiz:dataset:default:QbProfile
  QbCertificationRound: urn:jabiz:dataset:default:QbCertificationRound
  QbSponsorCertification: urn:jabiz:dataset:default:QbSponsorCertification
  QbCertificationRecord: urn:jabiz:dataset:default:QbCertificationRecord
params:
  userKey: { kind: { type: text, maxLength: 32 }, required: true, description: "The customer, md5(sponsor : user) as the transactions page gives it" }
  at:      { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the tree is for (the client's now) }
results:
  publicationId: { from: QbPublication.publicationId }
  userName:      { from: QbProfile.displayName }
  state:         { kind: { type: text, maxLength: 12 } }
list:
  key: [publicationId]
  sorts: [publicationId]
  defaultSort: { field: publicationId, asc: true }
permissions: [qb.publication.write]
---*/
-- The key names one of the sponsor's customers (somebody with a finished attempt on one of their publications): the
-- same md5 as qb.sponsor.transactions, so a key copied from there finds the same person; any other key finds nobody.
-- Then tree.TreeStates for that person on every submitted publication (TreeIT compares).
WITH node AS (
    SELECT p.{{QbPublication.publicationId}} AS id,
           p.{{QbPublication.publisherId}}   AS publisher_id,
           p.{{QbPublication.status}} = 'LIVE' AND p.{{QbPublication.startAt}} <= CAST(:at AS timestamptz)
               AND p.{{QbPublication.endAt}} > CAST(:at AS timestamptz) AS live
    FROM {{QbPublication}} p
    WHERE p.{{QbPublication.status}} <> 'DRAFT'
),
person AS (
    SELECT DISTINCT a.{{QbAttempt.userId}} AS user_id
    FROM {{QbAttempt}} a
    JOIN node n ON n.id = a.{{QbAttempt.publicationId}}
    WHERE a.{{QbAttempt.status}} <> 'IN_PROGRESS'
      AND md5(n.publisher_id || ':' || a.{{QbAttempt.userId}}) = :userKey
)
SELECT
    n.id                        AS publicationId,
    u.{{QbProfile.displayName}} AS userName,
    CASE WHEN EXISTS (SELECT 1 FROM {{QbAttempt}} a
                      WHERE a.{{QbAttempt.publicationId}} = n.id AND a.{{QbAttempt.userId}} = s.user_id
                        AND a.{{QbAttempt.status}} <> 'IN_PROGRESS' AND a.{{QbAttempt.passed}}) THEN 'LIT'
         WHEN EXISTS (SELECT 1 FROM {{QbAttempt}} a
                      WHERE a.{{QbAttempt.publicationId}} = n.id AND a.{{QbAttempt.userId}} = s.user_id
                        AND a.{{QbAttempt.status}} <> 'IN_PROGRESS') THEN 'ANSWERED'
         WHEN EXISTS (SELECT 1 FROM {{QbAttempt}} a
                      WHERE a.{{QbAttempt.publicationId}} = n.id AND a.{{QbAttempt.userId}} = s.user_id) THEN 'IN_PROGRESS'
         WHEN EXISTS (SELECT 1 FROM {{QbPublicationPrerequisite}} pr
                      WHERE pr.{{QbPublicationPrerequisite.publicationId}} = n.id
                        AND NOT ((pr.{{QbPublicationPrerequisite.requiredPublicationId}} IS NOT NULL AND EXISTS (
                              SELECT 1 FROM {{QbAttempt}} ra
                              WHERE ra.{{QbAttempt.publicationId}} = pr.{{QbPublicationPrerequisite.requiredPublicationId}}
                                  AND ra.{{QbAttempt.userId}} = s.user_id
                                AND ra.{{QbAttempt.status}} <> 'IN_PROGRESS'
                                AND (pr.{{QbPublicationPrerequisite.requirement}} = 'ANSWERED' OR ra.{{QbAttempt.passed}})))
                         OR (pr.{{QbPublicationPrerequisite.certification}} IS NOT NULL AND EXISTS (
                                SELECT 1
                                FROM (SELECT * FROM (VALUES ('SPONSOR_APPLY', 1, 31536000), ('REFERRER', 1, 31536000), ('TERMS_APP_UNDERSTOOD', 1, 31536000), ('CONTEST_RULES_UNDERSTOOD', 1, 31536000), ('TERMS_SPONSOR_UNDERSTOOD', 1, 31536000), ('BENEFIT_PROVIDER', 1, 31536000), ('BENEFIT_SPONSOR', 1, 31536000)) AS v(cert, ver, secs) UNION ALL SELECT 'sp:' || sc.{{QbSponsorCertification.certificationId}}, sc.{{QbSponsorCertification.certificationVersion}}, sc.{{QbSponsorCertification.validityDays}} * 86400 FROM {{QbSponsorCertification}} sc) AS cat(cert, ver, secs)
                                CROSS JOIN LATERAL (
                                    SELECT rec.{{QbCertificationRecord.certificationVersion}} AS ver, rec.{{QbCertificationRecord.at}} AS at,
                                           rec.{{QbCertificationRecord.validUntil}} AS until
                                    FROM {{QbCertificationRecord}} rec
                                    WHERE rec.{{QbCertificationRecord.certification}} = cat.cert
                                      AND rec.{{QbCertificationRecord.kind}} IN ('PASSED', 'GRANTED')
                                      AND rec.{{QbCertificationRecord.userId}} = s.user_id
                                    UNION ALL
                                    SELECT r2.{{QbCertificationRound.certificationVersion}}, a.{{QbAttempt.submittedAt}},
                                           a.{{QbAttempt.submittedAt}} + make_interval(secs => cat.secs)
                                    FROM {{QbCertificationRound}} r2
                                    JOIN {{QbAttempt}} a ON CAST(a.{{QbAttempt.publicationId}} AS text) = r2.{{QbCertificationRound.publicationId}}
                                    WHERE r2.{{QbCertificationRound.certification}} = cat.cert
                                      AND a.{{QbAttempt.passed}} AND a.{{QbAttempt.submittedAt}} IS NOT NULL
                                      AND a.{{QbAttempt.userId}} = s.user_id
                                      AND (r2.{{QbCertificationRound.closedAt}} IS NULL
                                           OR a.{{QbAttempt.submittedAt}} < r2.{{QbCertificationRound.closedAt}})) e
                                WHERE cat.cert = pr.{{QbPublicationPrerequisite.certification}}
                                  AND e.ver = cat.ver AND e.until > CAST(:at AS timestamptz)
                                  AND e.at > coalesce((SELECT max(rv.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} rv
                                                       WHERE rv.{{QbCertificationRecord.certification}} = cat.cert
                                                         AND rv.{{QbCertificationRecord.kind}} = 'REVOKED'
                                                             AND rv.{{QbCertificationRecord.userId}} = s.user_id), '-infinity'))))) THEN 'LOCKED'
         WHEN n.live THEN 'UNLOCKED'
         ELSE 'CLOSED' END   AS state
FROM person s
CROSS JOIN node n
LEFT JOIN {{QbProfile}} u ON u.{{QbProfile.userId}} = s.user_id
