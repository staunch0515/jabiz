/*---
id: qb.system.analytics-items
description: >-
  The items of one run of the analytics job - chunks of the publications to read, the ended publications to recount
  whole, and the run's own record last
entities: [QbPublicationCard, QbPublicationCounter, QbPublicationStartCounter, QbAnalyticsCursor, QbStatsRun]
datasets:
  QbPublicationCard: urn:jabiz:dataset:stats:QbPublicationCard
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  QbAnalyticsCursor: urn:jabiz:dataset:default:QbAnalyticsCursor
  QbStatsRun: urn:jabiz:dataset:default:QbStatsRun
params:
  recent:         { kind: { type: temporal, role: EVENT_TIME }, required: true, description: Publications ended after this time are read }
  finalizeBefore: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: Publications ended before this time are recounted once nothing is in progress }
  anywayBefore:   { kind: { type: temporal, role: EVENT_TIME }, required: true, description: Publications ended before this time are recounted even with attempts in progress }
  chunkSize:      { kind: { type: numeric, precision: 4, scale: 0 }, required: true, description: Publications per chunk }
results:
  itemKey:       { kind: { type: text, maxLength: 36 } }
  kind:          { kind: { type: text, maxLength: 10 } }
  firstId:       { from: QbPublicationCard.publicationId }
  lastId:        { from: QbPublicationCard.publicationId }
  previousRunAt: { from: QbStatsRun.refreshedAt }
list:
  sorts: [itemKey]
  defaultSort: { field: itemKey, asc: true }
  key: [itemKey]
permissions: [qb.stats.system]
---*/
-- One transaction per item (platform decision D42; Q9e1 review M3): a chunk that fails, or a recount that fails or
-- runs too long, leaves the others and is retried by the next run. Items run in key order: the chunks (c000000...)
-- and the recounts (the publication's key) before the run's record (zz-run), which only then takes the run's time.
-- Every row carries the previous run's time, which decides the hourly bucket (stats.AnalyticsBuckets).
-- The publications read: running ones by state, the cards ended after "recent", and ended ones whose cursor is not
-- finalized (three index ranges). A chunk is a range of their keys; uuids order as their text does. The recounts:
-- ended at least half an hour ago and not finalized, once nothing is in progress (both counters' sides in this one
-- statement) or a day after the end whatever is in progress (an attempt that never finishes, review L3).
WITH run AS (
    SELECT max(r.{{QbStatsRun.refreshedAt}}) AS prev
    FROM {{QbStatsRun}} r
    WHERE r.{{QbStatsRun.job}} = 'qb.analytics'),
due AS (
    SELECT k.{{QbPublicationCard.publicationId}} AS pid
    FROM {{QbPublicationCard}} k
    WHERE k.{{QbPublicationCard.status}} IN ('LIVE', 'PAUSED', 'FUNDS_SHORT')
    UNION
    SELECT k.{{QbPublicationCard.publicationId}} AS pid
    FROM {{QbPublicationCard}} k
    WHERE k.{{QbPublicationCard.status}} = 'ENDED' AND k.{{QbPublicationCard.endedAt}} > CAST(:recent AS timestamptz)
    UNION
    SELECT k.{{QbPublicationCard.publicationId}} AS pid
    FROM {{QbAnalyticsCursor}} c
    JOIN {{QbPublicationCard}} k ON k.{{QbPublicationCard.publicationId}} = c.{{QbAnalyticsCursor.publicationId}}
    WHERE c.{{QbAnalyticsCursor.finalizedAt}} IS NULL AND k.{{QbPublicationCard.status}} = 'ENDED'),
numbered AS (
    SELECT d.pid, (row_number() OVER (ORDER BY CAST(d.pid AS text)) - 1) / CAST(:chunkSize AS bigint) AS n
    FROM due d),
recounts AS (
    SELECT k.{{QbPublicationCard.publicationId}} AS pid
    FROM {{QbPublicationCard}} k
    LEFT JOIN {{QbAnalyticsCursor}} c ON c.{{QbAnalyticsCursor.publicationId}} = k.{{QbPublicationCard.publicationId}}
    LEFT JOIN {{QbPublicationCounter}} n ON n.{{QbPublicationCounter.publicationId}} = k.{{QbPublicationCard.publicationId}}
    LEFT JOIN {{QbPublicationStartCounter}} s
        ON s.{{QbPublicationStartCounter.publicationId}} = k.{{QbPublicationCard.publicationId}}
    WHERE k.{{QbPublicationCard.status}} = 'ENDED'
      AND k.{{QbPublicationCard.endedAt}} <= CAST(:finalizeBefore AS timestamptz)
      AND c.{{QbAnalyticsCursor.finalizedAt}} IS NULL
      AND k.{{QbPublicationCard.publicationId}} IN (SELECT d.pid FROM due d)
      AND (coalesce(s.{{QbPublicationStartCounter.starts}}, 0) = coalesce(n.{{QbPublicationCounter.completions}}, 0)
           OR k.{{QbPublicationCard.endedAt}} <= CAST(:anywayBefore AS timestamptz)))
SELECT CAST('c' || lpad(CAST(b.n AS text), 6, '0') AS varchar(36)) AS itemKey,
       CAST('CHUNK' AS varchar(10)) AS kind,
       CAST(min(CAST(b.pid AS text)) AS uuid) AS firstId,
       CAST(max(CAST(b.pid AS text)) AS uuid) AS lastId,
       (SELECT r.prev FROM run r) AS previousRunAt
FROM numbered b
GROUP BY b.n
UNION ALL
SELECT CAST(CAST(x.pid AS text) AS varchar(36)), CAST('RECOUNT' AS varchar(10)), x.pid, x.pid,
       (SELECT r.prev FROM run r)
FROM recounts x
UNION ALL
SELECT CAST('zz-run' AS varchar(36)), CAST('RUN' AS varchar(10)), CAST(NULL AS uuid), CAST(NULL AS uuid),
       (SELECT r.prev FROM run r)
