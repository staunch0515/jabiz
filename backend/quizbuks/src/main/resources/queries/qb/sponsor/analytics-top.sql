/*---
id: qb.sponsor.analytics.top
description: >-
  The sponsor's publications by their completions of the last seven days (M-11), with their Kudos spent then and their
  completions in all; the page asks for the first five
entities: [QbPublication, QbPublicationCounter, QbAnalyticsHour]
datasets:
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbAnalyticsHour: urn:jabiz:dataset:default:QbAnalyticsHour
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The client's now: the seven days end then" }
results:
  publicationId:    { from: QbPublication.publicationId }
  name:             { from: QbPublication.name }
  status:           { from: QbPublication.status }
  completions7d:    { kind: { type: numeric, precision: 12, scale: 0 } }
  spent7d:          { kind: { type: monetary, currency: JPY, scale: 0 } }
  completionsTotal: { from: QbPublicationCounter.completions }
list:
  sorts: [completions7d, spent7d, name]
  defaultSort: { field: completions7d, asc: false }
  key: [publicationId]
permissions: [qb.stats.read]
---*/
-- Only the sponsor's own publications with a completion in the hourly buckets of the seven days before "at" (the
-- job's, every ten minutes); the total is the counter's, live. Aggregates only. The buckets are found by the
-- sponsor's index (their publications all carry the caller as sponsor), and joined to their publications.
WITH mine AS (
    SELECT p.{{QbPublication.publicationId}} AS pid, p.{{QbPublication.sponsorId}} AS sponsor,
           p.{{QbPublication.name}} AS v_name, p.{{QbPublication.status}} AS v_status
    FROM {{QbPublication}} p),
recent AS (
    SELECT h.{{QbAnalyticsHour.publicationId}} AS pid, sum(h.{{QbAnalyticsHour.completions}}) AS v_completions,
           sum(h.{{QbAnalyticsHour.spent}}) AS v_spent
    FROM {{QbAnalyticsHour}} h
    WHERE h.{{QbAnalyticsHour.sponsorId}} IN (SELECT m.sponsor FROM mine m)
      AND h.{{QbAnalyticsHour.hourStart}} >= CAST(:at AS timestamptz) - interval '7 days'
      AND h.{{QbAnalyticsHour.hourStart}} < CAST(:at AS timestamptz)
    GROUP BY h.{{QbAnalyticsHour.publicationId}}
    HAVING sum(h.{{QbAnalyticsHour.completions}}) > 0)
SELECT
    m.pid                                                         AS publicationId,
    m.v_name                                                      AS name,
    m.v_status                                                    AS status,
    CAST(w.v_completions AS numeric(12, 0))                       AS completions7d,
    w.v_spent                                                     AS spent7d,
    coalesce(n.{{QbPublicationCounter.completions}}, 0) AS completionsTotal
FROM mine m
JOIN recent w ON w.pid = m.pid
LEFT JOIN {{QbPublicationCounter}} n ON n.{{QbPublicationCounter.publicationId}} = m.pid
