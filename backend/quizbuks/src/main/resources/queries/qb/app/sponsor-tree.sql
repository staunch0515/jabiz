/*---
id: qb.app.sponsor-tree
description: >-
  A sponsor's tree as the caller stands on it (Q85, D-Q85-6): the sponsor's public publications open now and those
  the caller answered, each with what it requires first (Q76) and the caller's state on it
entities: [QbPublicationCard, QbSponsorPage, QbPublicationPrerequisite, QbAttempt, QbCertificationRound, QbCertificationRecord]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsorPage: urn:jabiz:dataset:public:QbSponsorPage
  QbPublicationPrerequisite: urn:jabiz:dataset:default:QbPublicationPrerequisite
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
  QbCertificationRound: urn:jabiz:dataset:default:QbCertificationRound
  QbCertificationRecord: urn:jabiz:dataset:me:QbCertificationRecord
params:
  handle: { like: QbSponsorPage.handle, required: true, description: The sponsor's page (lowercase) }
  at:     { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the tree is for (the client's now) }
  lang:   { kind: { type: text, maxLength: 2 }, description: "The language to name the publications in (Q73); the card's own otherwise" }
results:
  publicationId: { from: QbPublicationCard.publicationId }
  name:          { kind: { type: text, maxLength: 200 } }
  kind:          { from: QbPublicationCard.kind }
  endAt:         { from: QbPublicationCard.endAt }
  maxReward:     { from: QbPublicationCard.maxReward }
  live:          { kind: { type: bool } }
  state:         { kind: { type: text, maxLength: 12 } }
  prerequisites: { kind: { type: text } }
list:
  key: [publicationId]
  sorts: [name, endAt]
  defaultSort: { field: name, asc: true }
permissions: [qb.me]
---*/
-- The nodes: the sponsor's live public publications (what their page lists), and those the caller already answered
-- however they stand now (D-Q85-8: a lit node stays). The page must be published, as its public templates require.
-- The caller's state is tree.TreeStates from their own attempts (the taker view holds the caller's only; TreeIT
-- compares); the edges name only nodes that are on the tree, so the page draws no dangling edge.
WITH page AS (
    SELECT sp.{{QbSponsorPage.userId}} AS publisher_id
    FROM {{QbSponsorPage}} sp
    WHERE sp.{{QbSponsorPage.handle}} = :handle
),
node AS (
    SELECT c.{{QbPublicationCard.publicationId}} AS id,
           c.{{QbPublicationCard.status}} = 'LIVE' AND c.{{QbPublicationCard.startAt}} <= CAST(:at AS timestamptz)
               AND c.{{QbPublicationCard.endAt}} > CAST(:at AS timestamptz) AS live
    FROM {{QbPublicationCard}} c
    JOIN page pg ON pg.publisher_id = c.{{QbPublicationCard.publisherId}}
    WHERE c.{{QbPublicationCard.publisherKind}} = 'SPONSOR'
      AND ((c.{{QbPublicationCard.status}} = 'LIVE' AND c.{{QbPublicationCard.audience}} = 'PUBLIC'
            AND c.{{QbPublicationCard.startAt}} <= CAST(:at AS timestamptz)
            AND c.{{QbPublicationCard.endAt}} > CAST(:at AS timestamptz))
           OR EXISTS (SELECT 1 FROM {{QbAttempt}} a
                      WHERE a.{{QbAttempt.publicationId}} = c.{{QbPublicationCard.publicationId}}
                        AND a.{{QbAttempt.status}} <> 'IN_PROGRESS'))
)
SELECT
    c.{{QbPublicationCard.publicationId}} AS publicationId,
    coalesce(CAST(c.{{QbPublicationCard.translations}} AS jsonb) -> CAST(:lang AS text) ->> 'name',
             c.{{QbPublicationCard.name}}) AS name,
    c.{{QbPublicationCard.kind}}          AS kind,
    c.{{QbPublicationCard.endAt}}         AS endAt,
    c.{{QbPublicationCard.maxReward}}     AS maxReward,
    n.live                                AS live,
    CASE WHEN EXISTS (SELECT 1 FROM {{QbAttempt}} a
                      WHERE a.{{QbAttempt.publicationId}} = n.id
                        AND a.{{QbAttempt.status}} <> 'IN_PROGRESS' AND a.{{QbAttempt.passed}}) THEN 'LIT'
         WHEN EXISTS (SELECT 1 FROM {{QbAttempt}} a
                      WHERE a.{{QbAttempt.publicationId}} = n.id
                        AND a.{{QbAttempt.status}} <> 'IN_PROGRESS') THEN 'ANSWERED'
         WHEN EXISTS (SELECT 1 FROM {{QbAttempt}} a
                      WHERE a.{{QbAttempt.publicationId}} = n.id) THEN 'IN_PROGRESS'
         WHEN EXISTS (SELECT 1 FROM {{QbPublicationPrerequisite}} pr
                      WHERE pr.{{QbPublicationPrerequisite.publicationId}} = n.id
                        AND NOT ((pr.{{QbPublicationPrerequisite.requiredPublicationId}} IS NOT NULL AND EXISTS (
                              SELECT 1 FROM {{QbAttempt}} ra
                              WHERE ra.{{QbAttempt.publicationId}} = pr.{{QbPublicationPrerequisite.requiredPublicationId}}
                                AND ra.{{QbAttempt.status}} <> 'IN_PROGRESS'
                                AND (pr.{{QbPublicationPrerequisite.requirement}} = 'ANSWERED' OR ra.{{QbAttempt.passed}})))
                         OR (pr.{{QbPublicationPrerequisite.certification}} IS NOT NULL AND EXISTS (
                                SELECT 1
                                FROM (VALUES ('SPONSOR_APPLY', 1, 31536000), ('REFERRER', 1, 31536000), ('TERMS_APP_UNDERSTOOD', 1, 31536000), ('CONTEST_RULES_UNDERSTOOD', 1, 31536000), ('TERMS_SPONSOR_UNDERSTOOD', 1, 31536000), ('BENEFIT_PROVIDER', 1, 31536000), ('BENEFIT_SPONSOR', 1, 31536000)) AS cat(cert, ver, secs)
                                CROSS JOIN LATERAL (
                                    SELECT rec.{{QbCertificationRecord.certificationVersion}} AS ver, rec.{{QbCertificationRecord.at}} AS at,
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
                                WHERE cat.cert = pr.{{QbPublicationPrerequisite.certification}}
                                  AND e.ver = cat.ver AND e.until > CAST(:at AS timestamptz)
                                  AND e.at > coalesce((SELECT max(rv.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} rv
                                                       WHERE rv.{{QbCertificationRecord.certification}} = cat.cert
                                                         AND rv.{{QbCertificationRecord.kind}} = 'REVOKED'), '-infinity'))))) THEN 'LOCKED'
         WHEN n.live THEN 'UNLOCKED'
         ELSE 'CLOSED' END                AS state,
    (SELECT CAST(coalesce(jsonb_agg(jsonb_build_object(
                'requiredPublicationId', pr.{{QbPublicationPrerequisite.requiredPublicationId}},
                'certification', pr.{{QbPublicationPrerequisite.certification}},
                'requirement', pr.{{QbPublicationPrerequisite.requirement}},
                'name', coalesce(CAST(rc.{{QbPublicationCard.translations}} AS jsonb) -> CAST(:lang AS text) ->> 'name',
                                 rc.{{QbPublicationCard.name}}),
                -- Q86: whether the caller holds the certification, and its current round to send them to.
                'held', pr.{{QbPublicationPrerequisite.certification}} IS NOT NULL AND EXISTS (
                    SELECT 1
                    FROM (VALUES ('SPONSOR_APPLY', 1, 31536000), ('REFERRER', 1, 31536000), ('TERMS_APP_UNDERSTOOD', 1, 31536000), ('CONTEST_RULES_UNDERSTOOD', 1, 31536000), ('TERMS_SPONSOR_UNDERSTOOD', 1, 31536000), ('BENEFIT_PROVIDER', 1, 31536000), ('BENEFIT_SPONSOR', 1, 31536000)) AS cat(cert, ver, secs)
                    CROSS JOIN LATERAL (
                        SELECT rec.{{QbCertificationRecord.certificationVersion}} AS ver, rec.{{QbCertificationRecord.at}} AS at,
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
                    WHERE cat.cert = pr.{{QbPublicationPrerequisite.certification}}
                      AND e.ver = cat.ver AND e.until > CAST(:at AS timestamptz)
                      AND e.at > coalesce((SELECT max(rv.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} rv
                                           WHERE rv.{{QbCertificationRecord.certification}} = cat.cert
                                             AND rv.{{QbCertificationRecord.kind}} = 'REVOKED'), '-infinity')),
                'roundPublicationId', (SELECT max(cr2.{{QbCertificationRound.publicationId}})
                                       FROM {{QbCertificationRound}} cr2
                                       JOIN {{QbPublicationCard}} rp ON CAST(rp.{{QbPublicationCard.publicationId}} AS text)
                                           = cr2.{{QbCertificationRound.publicationId}}
                                       WHERE cr2.{{QbCertificationRound.certification}} = pr.{{QbPublicationPrerequisite.certification}}
                                         AND cr2.{{QbCertificationRound.closedAt}} IS NULL
                                         AND rp.{{QbPublicationCard.status}} = 'LIVE' AND rp.{{QbPublicationCard.startAt}} <= CAST(:at AS timestamptz)
                                         AND rp.{{QbPublicationCard.endAt}} > CAST(:at AS timestamptz)))
                ORDER BY pr.{{QbPublicationPrerequisite.requiredPublicationId}}), '[]') AS text)
     FROM {{QbPublicationPrerequisite}} pr
     LEFT JOIN {{QbPublicationCard}} rc
         ON rc.{{QbPublicationCard.publicationId}} = pr.{{QbPublicationPrerequisite.requiredPublicationId}}
     WHERE pr.{{QbPublicationPrerequisite.publicationId}} = n.id) AS prerequisites
FROM node n
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = n.id
