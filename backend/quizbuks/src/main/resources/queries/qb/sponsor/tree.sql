/*---
id: qb.sponsor.tree
description: >-
  The sponsor's tree (Q85): every publication they submitted as a node with what it requires first (Q76), and how
  many of their customers lit it, answered it without passing, are on it, could start it or are locked out of it
entities: [QbPublication, QbPublicationPrerequisite, QbAttempt, QbCertificationRound, QbCertificationRecord, QbSponsorCertification]
datasets:
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbPublicationPrerequisite: urn:jabiz:dataset:sponsor:QbPublicationPrerequisite
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbCertificationRound: urn:jabiz:dataset:default:QbCertificationRound
  QbSponsorCertification: urn:jabiz:dataset:default:QbSponsorCertification
  QbCertificationRecord: urn:jabiz:dataset:default:QbCertificationRecord
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the tree is for (the client's now) }
results:
  publicationId: { from: QbPublication.publicationId }
  name:          { from: QbPublication.name }
  status:        { from: QbPublication.status }
  startAt:       { from: QbPublication.startAt }
  endAt:         { from: QbPublication.endAt }
  live:          { kind: { type: bool } }
  isolated:      { kind: { type: bool } }
  prerequisites: { kind: { type: text } }
  lit:           { kind: { type: numeric, precision: 9, scale: 0 } }
  answered:      { kind: { type: numeric, precision: 9, scale: 0 } }
  inProgress:    { kind: { type: numeric, precision: 9, scale: 0 } }
  unlocked:      { kind: { type: numeric, precision: 9, scale: 0 } }
  locked:        { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  key: [publicationId]
  sorts: [name, startAt]
  defaultSort: { field: name, asc: true }
permissions: [qb.publication.write]
---*/
-- The tree is not stored (D-Q85-1): the nodes are the sponsor's submitted publications (their view holds their own),
-- the edges the prerequisites. A person's state on a node is tree.TreeStates, the one definition, written here as
-- counts (TreeIT compares): lit = a finished attempt that passed, answered = finished without a pass, in progress =
-- under way; the rest are the sponsor's customers (anyone with a finished attempt on one of the sponsor's
-- publications) without an attempt on the node: unlocked when every prerequisite is met and the node is open now,
-- locked when one is not (D-Q85-5: a count, never who is missing what). The attempts are everyone's (the default
-- view), narrowed to the sponsor's own publications by the join; nothing of the taker is read but the attempt.
WITH node AS (
    SELECT p.{{QbPublication.publicationId}} AS id,
           p.{{QbPublication.status}} = 'LIVE' AND p.{{QbPublication.startAt}} <= CAST(:at AS timestamptz)
               AND p.{{QbPublication.endAt}} > CAST(:at AS timestamptz) AS live
    FROM {{QbPublication}} p
    WHERE p.{{QbPublication.status}} <> 'DRAFT'
),
customer AS (
    SELECT DISTINCT a.{{QbAttempt.userId}} AS user_id
    FROM {{QbAttempt}} a
    JOIN node n ON n.id = a.{{QbAttempt.publicationId}}
    WHERE a.{{QbAttempt.status}} <> 'IN_PROGRESS'
)
SELECT
    p.{{QbPublication.publicationId}} AS publicationId,
    p.{{QbPublication.name}}          AS name,
    p.{{QbPublication.status}}        AS status,
    p.{{QbPublication.startAt}}       AS startAt,
    p.{{QbPublication.endAt}}         AS endAt,
    n.live                            AS live,
    NOT EXISTS (SELECT 1 FROM {{QbPublicationPrerequisite}} e
                WHERE e.{{QbPublicationPrerequisite.publicationId}} = n.id
                   OR e.{{QbPublicationPrerequisite.requiredPublicationId}} = n.id) AS isolated,
    (SELECT CAST(coalesce(jsonb_agg(jsonb_build_object(
                'requiredPublicationId', pr.{{QbPublicationPrerequisite.requiredPublicationId}},
                'certification', pr.{{QbPublicationPrerequisite.certification}},
                -- Q88a: a sponsor's own certification is named by its row (the catalog's by the front ends' texts).
                'certificationNames', psc.{{QbSponsorCertification.name}},
                'requirement', pr.{{QbPublicationPrerequisite.requirement}})
                ORDER BY pr.{{QbPublicationPrerequisite.requiredPublicationId}}), '[]') AS text)
     FROM {{QbPublicationPrerequisite}} pr
     LEFT JOIN {{QbSponsorCertification}} psc
         ON 'sp:' || psc.{{QbSponsorCertification.certificationId}} = pr.{{QbPublicationPrerequisite.certification}}
     WHERE pr.{{QbPublicationPrerequisite.publicationId}} = n.id) AS prerequisites,
    (SELECT count(*) FROM {{QbAttempt}} a
     WHERE a.{{QbAttempt.publicationId}} = n.id AND a.{{QbAttempt.status}} <> 'IN_PROGRESS'
       AND a.{{QbAttempt.passed}}) AS lit,
    (SELECT count(*) FROM {{QbAttempt}} a
     WHERE a.{{QbAttempt.publicationId}} = n.id AND a.{{QbAttempt.status}} <> 'IN_PROGRESS'
       AND NOT coalesce(a.{{QbAttempt.passed}}, false)) AS answered,
    (SELECT count(*) FROM {{QbAttempt}} a
     WHERE a.{{QbAttempt.publicationId}} = n.id AND a.{{QbAttempt.status}} = 'IN_PROGRESS') AS inProgress,
    (SELECT count(*) FROM customer c
     WHERE n.live
       AND NOT EXISTS (SELECT 1 FROM {{QbAttempt}} a
                       WHERE a.{{QbAttempt.publicationId}} = n.id AND a.{{QbAttempt.userId}} = c.user_id)
       AND NOT EXISTS (SELECT 1 FROM {{QbPublicationPrerequisite}} pr
                       WHERE pr.{{QbPublicationPrerequisite.publicationId}} = n.id
                         AND NOT ((pr.{{QbPublicationPrerequisite.requiredPublicationId}} IS NOT NULL AND EXISTS (
                               SELECT 1 FROM {{QbAttempt}} ra
                               WHERE ra.{{QbAttempt.publicationId}} = pr.{{QbPublicationPrerequisite.requiredPublicationId}}
                                   AND ra.{{QbAttempt.userId}} = c.user_id
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
                                       AND rec.{{QbCertificationRecord.userId}} = c.user_id
                                     UNION ALL
                                     SELECT r2.{{QbCertificationRound.certificationVersion}}, a.{{QbAttempt.submittedAt}},
                                            a.{{QbAttempt.submittedAt}} + make_interval(secs => cat.secs)
                                     FROM {{QbCertificationRound}} r2
                                     JOIN {{QbAttempt}} a ON CAST(a.{{QbAttempt.publicationId}} AS text) = r2.{{QbCertificationRound.publicationId}}
                                     WHERE r2.{{QbCertificationRound.certification}} = cat.cert
                                       AND a.{{QbAttempt.passed}} AND a.{{QbAttempt.submittedAt}} IS NOT NULL
                                       AND a.{{QbAttempt.userId}} = c.user_id
                                       AND (r2.{{QbCertificationRound.closedAt}} IS NULL
                                            OR a.{{QbAttempt.submittedAt}} < r2.{{QbCertificationRound.closedAt}})) e
                                 WHERE cat.cert = pr.{{QbPublicationPrerequisite.certification}}
                                   AND e.ver = cat.ver AND e.until > CAST(:at AS timestamptz)
                                   AND e.at > coalesce((SELECT max(rv.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} rv
                                                        WHERE rv.{{QbCertificationRecord.certification}} = cat.cert
                                                          AND rv.{{QbCertificationRecord.kind}} = 'REVOKED'
                                                              AND rv.{{QbCertificationRecord.userId}} = c.user_id), '-infinity')))))) AS unlocked,
    (SELECT count(*) FROM customer c
     WHERE NOT EXISTS (SELECT 1 FROM {{QbAttempt}} a
                       WHERE a.{{QbAttempt.publicationId}} = n.id AND a.{{QbAttempt.userId}} = c.user_id)
       AND EXISTS (SELECT 1 FROM {{QbPublicationPrerequisite}} pr
                   WHERE pr.{{QbPublicationPrerequisite.publicationId}} = n.id
                     AND NOT ((pr.{{QbPublicationPrerequisite.requiredPublicationId}} IS NOT NULL AND EXISTS (
                           SELECT 1 FROM {{QbAttempt}} ra
                           WHERE ra.{{QbAttempt.publicationId}} = pr.{{QbPublicationPrerequisite.requiredPublicationId}}
                               AND ra.{{QbAttempt.userId}} = c.user_id
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
                                   AND rec.{{QbCertificationRecord.userId}} = c.user_id
                                 UNION ALL
                                 SELECT r2.{{QbCertificationRound.certificationVersion}}, a.{{QbAttempt.submittedAt}},
                                        a.{{QbAttempt.submittedAt}} + make_interval(secs => cat.secs)
                                 FROM {{QbCertificationRound}} r2
                                 JOIN {{QbAttempt}} a ON CAST(a.{{QbAttempt.publicationId}} AS text) = r2.{{QbCertificationRound.publicationId}}
                                 WHERE r2.{{QbCertificationRound.certification}} = cat.cert
                                   AND a.{{QbAttempt.passed}} AND a.{{QbAttempt.submittedAt}} IS NOT NULL
                                   AND a.{{QbAttempt.userId}} = c.user_id
                                   AND (r2.{{QbCertificationRound.closedAt}} IS NULL
                                        OR a.{{QbAttempt.submittedAt}} < r2.{{QbCertificationRound.closedAt}})) e
                             WHERE cat.cert = pr.{{QbPublicationPrerequisite.certification}}
                               AND e.ver = cat.ver AND e.until > CAST(:at AS timestamptz)
                               AND e.at > coalesce((SELECT max(rv.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} rv
                                                    WHERE rv.{{QbCertificationRecord.certification}} = cat.cert
                                                      AND rv.{{QbCertificationRecord.kind}} = 'REVOKED'
                                                          AND rv.{{QbCertificationRecord.userId}} = c.user_id), '-infinity')))))) AS locked
FROM node n
JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = n.id
