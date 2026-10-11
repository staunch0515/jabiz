/*---
id: qb.system.publication-stats
description: Completions of the last hour, day, week and month of some publications, for the statistics job
entities: [QbPublicationCard, QbAttempt, QbPublicationStats]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbPublicationStats: urn:jabiz:dataset:default:QbPublicationStats
params:
  at:  { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time counted back from }
  ids: { like: QbPublicationCard.publicationId, list: true, required: true, description: The publications of one chunk }
results:
  publicationId: { from: QbPublicationCard.publicationId }
  last1h:        { kind: { type: numeric, precision: 9, scale: 0 } }
  last24h:       { kind: { type: numeric, precision: 9, scale: 0 } }
  last7d:        { kind: { type: numeric, precision: 9, scale: 0 } }
  last30d:       { kind: { type: numeric, precision: 9, scale: 0 } }
  statsVersion:  { from: QbPublicationStats.version }
  oldLast1h:     { from: QbPublicationStats.last1h }
  oldLast24h:    { from: QbPublicationStats.last24h }
  oldLast7d:     { from: QbPublicationStats.last7d }
  oldLast30d:    { from: QbPublicationStats.last30d }
list:
  key: [publicationId]
permissions: [qb.play.system]
---*/
-- One chunk of the job (play.PublicationStats chooses the publications): the attempts finished within the month
-- before "at", through the index on (publication, submission time); the stored counts beside them, so the job writes
-- only what changed.
SELECT
    c.{{QbPublicationCard.publicationId}} AS publicationId,
    CAST(count(a.{{QbAttempt.attemptId}}) FILTER (
        WHERE a.{{QbAttempt.submittedAt}} > CAST(:at AS timestamptz) - interval '1 hour') AS numeric(9, 0)) AS last1h,
    CAST(count(a.{{QbAttempt.attemptId}}) FILTER (
        WHERE a.{{QbAttempt.submittedAt}} > CAST(:at AS timestamptz) - interval '24 hours') AS numeric(9, 0)) AS last24h,
    CAST(count(a.{{QbAttempt.attemptId}}) FILTER (
        WHERE a.{{QbAttempt.submittedAt}} > CAST(:at AS timestamptz) - interval '7 days') AS numeric(9, 0)) AS last7d,
    CAST(count(a.{{QbAttempt.attemptId}}) AS numeric(9, 0)) AS last30d,
    s.{{QbPublicationStats.version}}      AS statsVersion,
    s.{{QbPublicationStats.last1h}}       AS oldLast1h,
    s.{{QbPublicationStats.last24h}}      AS oldLast24h,
    s.{{QbPublicationStats.last7d}}       AS oldLast7d,
    s.{{QbPublicationStats.last30d}}      AS oldLast30d
FROM {{QbPublicationCard}} c
LEFT JOIN {{QbAttempt}} a ON a.{{QbAttempt.publicationId}} = c.{{QbPublicationCard.publicationId}}
    AND a.{{QbAttempt.submittedAt}} > CAST(:at AS timestamptz) - interval '30 days'
    AND a.{{QbAttempt.submittedAt}} <= CAST(:at AS timestamptz)
LEFT JOIN {{QbPublicationStats}} s ON s.{{QbPublicationStats.publicationId}} = c.{{QbPublicationCard.publicationId}}
WHERE c.{{QbPublicationCard.publicationId}} = ANY(:ids)
GROUP BY c.{{QbPublicationCard.publicationId}}, s.{{QbPublicationStats.version}}, s.{{QbPublicationStats.last1h}},
    s.{{QbPublicationStats.last24h}}, s.{{QbPublicationStats.last7d}}, s.{{QbPublicationStats.last30d}}
