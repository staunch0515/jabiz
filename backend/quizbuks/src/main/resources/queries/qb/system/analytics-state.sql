/*---
id: qb.system.analytics-state
description: >-
  One chunk of the analytics job - per publication its card, both sides of its counters, its cursor, the first views
  since the cursor's, the hourly bucket the run adds to and the minimum group
entities: [QbPublicationCard, QbPublicationCounter, QbPublicationStartCounter, QbAnalyticsCursor, QbAnalyticsHour, QbPublicationView, SysParam]
datasets:
  QbPublicationCard: urn:jabiz:dataset:stats:QbPublicationCard
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  QbAnalyticsCursor: urn:jabiz:dataset:default:QbAnalyticsCursor
  QbAnalyticsHour: urn:jabiz:dataset:default:QbAnalyticsHour
  QbPublicationView: urn:jabiz:dataset:stats:QbPublicationView
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  firstId: { like: QbPublicationCard.publicationId, description: "The chunk's first publication (none: no rows)" }
  lastId:  { like: QbPublicationCard.publicationId, description: "The chunk's last publication" }
  recent:  { kind: { type: temporal, role: EVENT_TIME }, required: true, description: Publications ended after this time are read }
  bucket:  { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The hour the run adds to (before a publication's start)" }
  viewsTo: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: First views up to this time are counted }
results:
  publicationId:   { from: QbPublicationCard.publicationId }
  publisherKind:   { from: QbPublicationCard.publisherKind }
  publisherId:     { from: QbPublicationCard.publisherId }
  status:          { from: QbPublicationCard.status }
  endedAt:         { from: QbPublicationCard.endedAt }
  wentOnAt:        { from: QbPublicationCard.wentOnAt }
  bucketStart:     { kind: { type: temporal, role: EVENT_TIME } }
  starts:          { from: QbPublicationStartCounter.starts }
  completions:     { from: QbPublicationCounter.completions }
  passes:          { from: QbPublicationCounter.passes }
  winners:         { from: QbPublicationCounter.winners }
  spent:           { from: QbPublicationCounter.spent }
  newViews:        { kind: { type: numeric, precision: 12, scale: 0 } }
  cursorVersion:   { from: QbAnalyticsCursor.version }
  lastViews:       { from: QbAnalyticsCursor.views }
  lastStarts:      { from: QbAnalyticsCursor.starts }
  lastCompletions: { from: QbAnalyticsCursor.completions }
  lastPasses:      { from: QbAnalyticsCursor.passes }
  lastWinners:     { from: QbAnalyticsCursor.winners }
  lastSpent:       { from: QbAnalyticsCursor.spent }
  analysed:        { from: QbAnalyticsCursor.analysed }
  submitted:       { from: QbAnalyticsCursor.submitted }
  expired:         { from: QbAnalyticsCursor.expired }
  durationSec:     { from: QbAnalyticsCursor.durationSec }
  questions:       { from: QbAnalyticsCursor.questions }
  shownAnalysed:   { from: QbAnalyticsCursor.shownAnalysed }
  shownSubmitted:  { from: QbAnalyticsCursor.shownSubmitted }
  finalizedAt:     { from: QbAnalyticsCursor.finalizedAt }
  hourId:          { from: QbAnalyticsHour.hourId }
  hourVersion:     { from: QbAnalyticsHour.version }
  hourViews:       { from: QbAnalyticsHour.views }
  hourStarts:      { from: QbAnalyticsHour.starts }
  hourCompletions: { from: QbAnalyticsHour.completions }
  hourPasses:      { from: QbAnalyticsHour.passes }
  hourWinners:     { from: QbAnalyticsHour.winners }
  hourSpent:       { from: QbAnalyticsHour.spent }
  hourGap:         { from: QbAnalyticsHour.gap }
  minGroup:        { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  key: [publicationId]
permissions: [qb.stats.system]
---*/
-- Both sides of the counters in this one statement (backend/quizbuks/CLAUDE.md, Q10a4), as committed: the job takes no
-- lock. The bucket is the run's (the previous run's hour after an hour began), or the hour the publication went on if
-- later; floor(epoch / 3600) is the UTC hour whatever the session's time zone. The first views since the cursor's
-- through the index on (publication, first view). The minimum group as every analytics template reads it: the
-- parameter qb.stats.min-group if a whole number, else 5, never below 3 (stats.MinGroup).
WITH params AS (
    SELECT greatest(3, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,9}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 5)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.stats.min-group'),
chunk AS (
    SELECT k.{{QbPublicationCard.publicationId}} AS pid, k.{{QbPublicationCard.publisherKind}} AS publisher_kind,
           k.{{QbPublicationCard.publisherId}} AS publisher,
           k.{{QbPublicationCard.status}} AS status, k.{{QbPublicationCard.endedAt}} AS ended,
           k.{{QbPublicationCard.wentOnAt}} AS went_on,
           greatest(CAST(:bucket AS timestamptz),
                    to_timestamp(floor(extract(epoch FROM k.{{QbPublicationCard.wentOnAt}}) / 3600) * 3600)) AS bucket
    FROM {{QbPublicationCard}} k
    LEFT JOIN {{QbAnalyticsCursor}} f ON f.{{QbAnalyticsCursor.publicationId}} = k.{{QbPublicationCard.publicationId}}
    WHERE k.{{QbPublicationCard.publicationId}} BETWEEN CAST(:firstId AS uuid) AND CAST(:lastId AS uuid)
      AND (k.{{QbPublicationCard.status}} IN ('LIVE', 'PAUSED', 'FUNDS_SHORT')
           OR (k.{{QbPublicationCard.status}} = 'ENDED'
               AND (k.{{QbPublicationCard.endedAt}} > CAST(:recent AS timestamptz)
                    OR (f.{{QbAnalyticsCursor.publicationId}} IS NOT NULL
                        AND f.{{QbAnalyticsCursor.finalizedAt}} IS NULL)))))
SELECT
    x.pid                                   AS publicationId,
    x.publisher_kind                        AS publisherKind,
    x.publisher                             AS publisherId,
    x.status                                AS status,
    x.ended                                 AS endedAt,
    x.went_on                               AS wentOnAt,
    x.bucket                                AS bucketStart,
    coalesce(s.{{QbPublicationStartCounter.starts}}, 0) AS starts,
    coalesce(n.{{QbPublicationCounter.completions}}, 0) AS completions,
    coalesce(n.{{QbPublicationCounter.passes}}, 0)      AS passes,
    coalesce(n.{{QbPublicationCounter.winners}}, 0)     AS winners,
    coalesce(n.{{QbPublicationCounter.spent}}, 0)       AS spent,
    CAST((SELECT count(*) FROM {{QbPublicationView}} v
          WHERE v.{{QbPublicationView.publicationId}} = x.pid
            AND (c.{{QbAnalyticsCursor.viewsUntil}} IS NULL
                 OR v.{{QbPublicationView.firstViewedAt}} > c.{{QbAnalyticsCursor.viewsUntil}})
            AND v.{{QbPublicationView.firstViewedAt}} <= CAST(:viewsTo AS timestamptz)) AS numeric(12, 0)) AS newViews,
    c.{{QbAnalyticsCursor.version}}         AS cursorVersion,
    c.{{QbAnalyticsCursor.views}}           AS lastViews,
    c.{{QbAnalyticsCursor.starts}}          AS lastStarts,
    c.{{QbAnalyticsCursor.completions}}     AS lastCompletions,
    c.{{QbAnalyticsCursor.passes}}          AS lastPasses,
    c.{{QbAnalyticsCursor.winners}}         AS lastWinners,
    c.{{QbAnalyticsCursor.spent}}           AS lastSpent,
    c.{{QbAnalyticsCursor.analysed}}        AS analysed,
    c.{{QbAnalyticsCursor.submitted}}       AS submitted,
    c.{{QbAnalyticsCursor.expired}}         AS expired,
    c.{{QbAnalyticsCursor.durationSec}}     AS durationSec,
    c.{{QbAnalyticsCursor.questions}}       AS questions,
    c.{{QbAnalyticsCursor.shownAnalysed}}   AS shownAnalysed,
    c.{{QbAnalyticsCursor.shownSubmitted}}  AS shownSubmitted,
    c.{{QbAnalyticsCursor.finalizedAt}}     AS finalizedAt,
    h.{{QbAnalyticsHour.hourId}}            AS hourId,
    h.{{QbAnalyticsHour.version}}           AS hourVersion,
    h.{{QbAnalyticsHour.views}}             AS hourViews,
    h.{{QbAnalyticsHour.starts}}            AS hourStarts,
    h.{{QbAnalyticsHour.completions}}       AS hourCompletions,
    h.{{QbAnalyticsHour.passes}}            AS hourPasses,
    h.{{QbAnalyticsHour.winners}}           AS hourWinners,
    h.{{QbAnalyticsHour.spent}}             AS hourSpent,
    h.{{QbAnalyticsHour.gap}}               AS hourGap,
    CAST(m.k AS numeric(9, 0))              AS minGroup
FROM chunk x
CROSS JOIN params m
LEFT JOIN {{QbPublicationCounter}} n ON n.{{QbPublicationCounter.publicationId}} = x.pid
LEFT JOIN {{QbPublicationStartCounter}} s ON s.{{QbPublicationStartCounter.publicationId}} = x.pid
LEFT JOIN {{QbAnalyticsCursor}} c ON c.{{QbAnalyticsCursor.publicationId}} = x.pid
LEFT JOIN {{QbAnalyticsHour}} h ON h.{{QbAnalyticsHour.publicationId}} = x.pid
    AND h.{{QbAnalyticsHour.hourStart}} = x.bucket
