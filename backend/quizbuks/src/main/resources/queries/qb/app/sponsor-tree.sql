/*---
id: qb.app.sponsor-tree
description: >-
  A sponsor's tree as the caller stands on it (Q85, D-Q85-6): the sponsor's public publications open now and those
  the caller answered, each with what it requires first (Q76) and the caller's state on it
entities: [QbPublicationCard, QbSponsorPage, QbPublicationPrerequisite, QbAttempt]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsorPage: urn:jabiz:dataset:public:QbSponsorPage
  QbPublicationPrerequisite: urn:jabiz:dataset:default:QbPublicationPrerequisite
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
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
                        AND NOT EXISTS (SELECT 1 FROM {{QbAttempt}} ra
                                        WHERE ra.{{QbAttempt.publicationId}} = pr.{{QbPublicationPrerequisite.requiredPublicationId}}
                                          AND ra.{{QbAttempt.status}} <> 'IN_PROGRESS'
                                          AND (pr.{{QbPublicationPrerequisite.requirement}} = 'ANSWERED'
                                               OR ra.{{QbAttempt.passed}}))) THEN 'LOCKED'
         WHEN n.live THEN 'UNLOCKED'
         ELSE 'CLOSED' END                AS state,
    (SELECT CAST(coalesce(jsonb_agg(jsonb_build_object(
                'requiredPublicationId', pr.{{QbPublicationPrerequisite.requiredPublicationId}},
                'requirement', pr.{{QbPublicationPrerequisite.requirement}},
                'name', coalesce(CAST(rc.{{QbPublicationCard.translations}} AS jsonb) -> CAST(:lang AS text) ->> 'name',
                                 rc.{{QbPublicationCard.name}}))
                ORDER BY pr.{{QbPublicationPrerequisite.requiredPublicationId}}), '[]') AS text)
     FROM {{QbPublicationPrerequisite}} pr
     JOIN {{QbPublicationCard}} rc
         ON rc.{{QbPublicationCard.publicationId}} = pr.{{QbPublicationPrerequisite.requiredPublicationId}}
     WHERE pr.{{QbPublicationPrerequisite.publicationId}} = n.id) AS prerequisites
FROM node n
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = n.id
