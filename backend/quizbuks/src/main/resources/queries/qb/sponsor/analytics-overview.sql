/*---
id: qb.sponsor.analytics.overview
description: >-
  One of the sponsor's publications in figures (M-40, M-41, M-43) - first views, starts, completions, passes, winners,
  Kudos spent, the completion rate, the average time and the timeouts (only over the minimum group), the funnel with
  its largest drop, and the App's recent completion counts
entities: [QbPublication, QbPublicationCounter, QbPublicationStartCounter, QbAnalyticsCursor, QbPublicationStats, QbStatsRun, SysParam]
datasets:
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  QbAnalyticsCursor: urn:jabiz:dataset:default:QbAnalyticsCursor
  QbPublicationStats: urn:jabiz:dataset:default:QbPublicationStats
  QbStatsRun: urn:jabiz:dataset:default:QbStatsRun
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  publicationId: { like: QbPublication.publicationId, required: true, description: The publication }
results:
  publicationId:    { from: QbPublication.publicationId }
  name:             { from: QbPublication.name }
  status:           { from: QbPublication.status }
  views:            { kind: { type: numeric, precision: 12, scale: 0 } }
  starts:           { kind: { type: numeric, precision: 12, scale: 0 } }
  completions:      { kind: { type: numeric, precision: 12, scale: 0 } }
  passes:           { kind: { type: numeric, precision: 12, scale: 0 } }
  winners:          { kind: { type: numeric, precision: 12, scale: 0 } }
  spent:            { kind: { type: monetary, currency: JPY, scale: 0 } }
  inProgress:       { kind: { type: numeric, precision: 12, scale: 0 } }
  completionRate:   { kind: { type: numeric, precision: 5, scale: 4 } }
  funnelViews:      { kind: { type: numeric, precision: 12, scale: 0 } }
  viewsBelowStarts: { kind: { type: bool } }
  viewToStart:      { kind: { type: numeric, precision: 5, scale: 4 } }
  startToComplete:  { kind: { type: numeric, precision: 5, scale: 4 } }
  completeToPass:   { kind: { type: numeric, precision: 5, scale: 4 } }
  largestDrop:      { kind: { type: text, maxLength: 20 } }
  basedOn:          { kind: { type: numeric, precision: 12, scale: 0 } }
  avgDurationSec:   { kind: { type: numeric, precision: 9, scale: 1 } }
  timeouts:         { kind: { type: numeric, precision: 12, scale: 0 } }
  suppressed:       { kind: { type: bool } }
  minGroup:         { kind: { type: numeric, precision: 3, scale: 0 } }
  last1h:           { from: QbPublicationStats.last1h }
  last24h:          { from: QbPublicationStats.last24h }
  last7d:           { from: QbPublicationStats.last7d }
  last30d:          { from: QbPublicationStats.last30d }
  appCountedAt:     { from: QbPublicationStats.refreshedAt }
  shownAt:          { from: QbAnalyticsCursor.shownAt }
  refreshedAt:      { from: QbStatsRun.refreshedAt }
list:
  key: [publicationId]
permissions: [qb.stats.read]
---*/
-- One row for a publication of the sponsor's own, none for anybody else's (the sponsor's dataset of publications).
-- The counts are the counters' (both sides in this one statement, live; E-3); first views the job's (every ten
-- minutes, five minutes behind). Counts are not held back (E-4). The funnel is first views -> starts -> completions ->
-- passes, each rate against the stage before; first views below starts (a link opened without the detail page, a view
-- not recorded) are shown as starts and flagged. The largest drop is the stage losing the largest share, the earlier
-- on a tie, none when nothing is lost. The average time (of the attempts submitted in time, E-6) and the timeouts are
-- the job's shown figures (stats.MinGroup: they change only after at least k new completions, the time after k new
-- attempts submitted in time) and only over at least k of them: k is qb.stats.min-group if a whole number, else 5, never below 3, as in every analytics template. The
-- App's four windows are its own counts (qb_publication_stats), shown beside the trend as they are.
WITH params AS (
    SELECT greatest(3, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,3}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 5)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.stats.min-group'),
run AS (
    SELECT max(r.{{QbStatsRun.refreshedAt}}) AS refreshed
    FROM {{QbStatsRun}} r
    WHERE r.{{QbStatsRun.job}} = 'qb.analytics'),
base AS (
    SELECT
        p.{{QbPublication.publicationId}} AS pid, p.{{QbPublication.name}} AS v_name,
        p.{{QbPublication.status}} AS v_status,
        coalesce(c.{{QbAnalyticsCursor.views}}, 0) AS v_views,
        coalesce(s.{{QbPublicationStartCounter.starts}}, 0) AS v_starts,
        coalesce(n.{{QbPublicationCounter.completions}}, 0) AS v_completions,
        coalesce(n.{{QbPublicationCounter.passes}}, 0) AS v_passes,
        coalesce(n.{{QbPublicationCounter.winners}}, 0) AS v_winners,
        coalesce(n.{{QbPublicationCounter.spent}}, 0) AS v_spent,
        coalesce(c.{{QbAnalyticsCursor.shownAnalysed}}, 0) AS x_analysed,
        coalesce(c.{{QbAnalyticsCursor.shownSubmitted}}, 0) AS x_submitted,
        coalesce(c.{{QbAnalyticsCursor.shownExpired}}, 0) AS x_expired,
        coalesce(c.{{QbAnalyticsCursor.shownDurationSec}}, 0) AS x_duration,
        c.{{QbAnalyticsCursor.shownAt}} AS x_at,
        m.k
    FROM {{QbPublication}} p
    CROSS JOIN params m
    LEFT JOIN {{QbPublicationCounter}} n ON n.{{QbPublicationCounter.publicationId}} = p.{{QbPublication.publicationId}}
    LEFT JOIN {{QbPublicationStartCounter}} s
        ON s.{{QbPublicationStartCounter.publicationId}} = p.{{QbPublication.publicationId}}
    LEFT JOIN {{QbAnalyticsCursor}} c ON c.{{QbAnalyticsCursor.publicationId}} = p.{{QbPublication.publicationId}}
    WHERE p.{{QbPublication.publicationId}} = :publicationId),
funnel AS (
    SELECT b.*,
           greatest(b.v_views, b.v_starts) AS fv,
           CASE WHEN greatest(b.v_views, b.v_starts) > 0
                THEN 1 - CAST(b.v_starts AS numeric) / greatest(b.v_views, b.v_starts) END AS d1,
           CASE WHEN b.v_starts > 0 THEN 1 - CAST(b.v_completions AS numeric) / b.v_starts END AS d2,
           CASE WHEN b.v_completions > 0 THEN 1 - CAST(b.v_passes AS numeric) / b.v_completions END AS d3
    FROM base b)
SELECT
    f.pid                                                         AS publicationId,
    f.v_name                                                      AS name,
    f.v_status                                                    AS status,
    CAST(f.v_views AS numeric(12, 0))                             AS views,
    CAST(f.v_starts AS numeric(12, 0))                            AS starts,
    CAST(f.v_completions AS numeric(12, 0))                       AS completions,
    CAST(f.v_passes AS numeric(12, 0))                            AS passes,
    CAST(f.v_winners AS numeric(12, 0))                           AS winners,
    f.v_spent                                                     AS spent,
    CAST(f.v_starts - f.v_completions AS numeric(12, 0))          AS inProgress,
    CASE WHEN f.v_starts > 0 THEN round(CAST(f.v_completions AS numeric) / f.v_starts, 4) END AS completionRate,
    CAST(f.fv AS numeric(12, 0))                                  AS funnelViews,
    f.v_views < f.v_starts                                        AS viewsBelowStarts,
    CASE WHEN f.fv > 0 THEN round(CAST(f.v_starts AS numeric) / f.fv, 4) END AS viewToStart,
    CASE WHEN f.v_starts > 0 THEN round(CAST(f.v_completions AS numeric) / f.v_starts, 4) END AS startToComplete,
    CASE WHEN f.v_completions > 0 THEN round(CAST(f.v_passes AS numeric) / f.v_completions, 4) END AS completeToPass,
    CAST(CASE
        WHEN greatest(coalesce(f.d1, -1), coalesce(f.d2, -1), coalesce(f.d3, -1)) <= 0 THEN NULL
        WHEN coalesce(f.d1, -1) >= coalesce(f.d2, -1) AND coalesce(f.d1, -1) >= coalesce(f.d3, -1) THEN 'VIEW_START'
        WHEN coalesce(f.d2, -1) >= coalesce(f.d3, -1) THEN 'START_COMPLETE'
        ELSE 'COMPLETE_PASS' END AS varchar(20))                  AS largestDrop,
    CAST(f.x_analysed AS numeric(12, 0))                          AS basedOn,
    CASE WHEN f.x_analysed >= f.k AND f.x_submitted >= f.k
         THEN CAST(round(f.x_duration / f.x_submitted, 1) AS numeric(9, 1)) END AS avgDurationSec,
    CASE WHEN f.x_analysed >= f.k THEN CAST(f.x_expired AS numeric(12, 0)) END AS timeouts,
    f.x_analysed < f.k                                            AS suppressed,
    CAST(f.k AS numeric(3, 0))                                    AS minGroup,
    w.{{QbPublicationStats.last1h}}                               AS last1h,
    w.{{QbPublicationStats.last24h}}                              AS last24h,
    w.{{QbPublicationStats.last7d}}                               AS last7d,
    w.{{QbPublicationStats.last30d}}                              AS last30d,
    w.{{QbPublicationStats.refreshedAt}}                          AS appCountedAt,
    f.x_at                                                        AS shownAt,
    r.refreshed                                                   AS refreshedAt
FROM funnel f
CROSS JOIN run r
LEFT JOIN {{QbPublicationStats}} w ON w.{{QbPublicationStats.publicationId}} = f.pid
