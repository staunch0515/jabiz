/*---
id: qb.app.sponsor-publications
description: >-
  The caller's own publications that are on (Q68: scheduled, live, paused or short of funds) as the app shows them to a
  sponsor: the counts, what was spent of the budget, until when, and who paused one that is paused
entities: [QbPublication, QbPublicationCard, QbPublicationCounter, QbPublicationStartCounter]
datasets:
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
results:
  publicationId: { from: QbPublication.publicationId }
  name:          { from: QbPublication.name }
  cover:         { from: QbPublicationCard.cover }
  status:        { from: QbPublication.status }
  pausedBy:      { from: QbPublication.pausedBy }
  rewardMethod:  { from: QbPublication.rewardMethod }
  starts:        { from: QbPublicationStartCounter.starts }
  completions:   { from: QbPublicationCounter.completions }
  passes:        { from: QbPublicationCounter.passes }
  answering:     { kind: { type: numeric, precision: 12, scale: 0 } }
  spent:         { from: QbPublicationCounter.spent }
  budgetCap:     { from: QbPublication.budgetCap }
  startAt:       { from: QbPublication.startAt }
  endAt:         { from: QbPublication.endAt }
list:
  sorts: [startAt, endAt, name]
  defaultSort: { field: endAt, asc: true }
  key: [publicationId]
permissions: [qb.sponsor.app]
---*/
-- Only the caller's publications (the sponsor's dataset of publications: another's are not there), those that are on;
-- drafts, reviews and ended ones are the sponsor console's. The counts as qb.sponsor.publications reads them: both sides
-- of the counters in this one statement (Q10a4), answering = starts - completions. The cover is the card's (Q64).
SELECT
    p.{{QbPublication.publicationId}}  AS publicationId,
    p.{{QbPublication.name}}           AS name,
    CAST(k.{{QbPublicationCard.cover}} AS text) AS cover,
    p.{{QbPublication.status}}         AS status,
    p.{{QbPublication.pausedBy}}       AS pausedBy,
    p.{{QbPublication.rewardMethod}}   AS rewardMethod,
    coalesce(s.{{QbPublicationStartCounter.starts}}, 0) AS starts,
    coalesce(c.{{QbPublicationCounter.completions}}, 0) AS completions,
    coalesce(c.{{QbPublicationCounter.passes}}, 0)      AS passes,
    CAST(greatest(coalesce(s.{{QbPublicationStartCounter.starts}}, 0)
                  - coalesce(c.{{QbPublicationCounter.completions}}, 0), 0) AS numeric(12, 0)) AS answering,
    coalesce(c.{{QbPublicationCounter.spent}}, 0)       AS spent,
    p.{{QbPublication.budgetCap}}      AS budgetCap,
    p.{{QbPublication.startAt}}        AS startAt,
    p.{{QbPublication.endAt}}          AS endAt
FROM {{QbPublication}} p
LEFT JOIN {{QbPublicationCard}} k ON k.{{QbPublicationCard.publicationId}} = p.{{QbPublication.publicationId}}
LEFT JOIN {{QbPublicationCounter}} c ON c.{{QbPublicationCounter.publicationId}} = p.{{QbPublication.publicationId}}
LEFT JOIN {{QbPublicationStartCounter}} s
    ON s.{{QbPublicationStartCounter.publicationId}} = p.{{QbPublication.publicationId}}
WHERE p.{{QbPublication.status}} IN ('SCHEDULED', 'LIVE', 'PAUSED', 'FUNDS_SHORT')
