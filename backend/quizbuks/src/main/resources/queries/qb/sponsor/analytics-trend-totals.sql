/*---
id: qb.sponsor.analytics.trend-totals
description: >-
  The totals of the sponsor's trend for the same parameters (Q9e2 review L1) - first views, starts, completions,
  passes, winners and Kudos spent over every bucket qb.sponsor.analytics.trend gives, so that the page adds nothing up
entities: [QbPublicationCard, QbAnalyticsHour, QbStatsRun]
datasets:
  QbPublicationCard: urn:jabiz:dataset:sponsor:QbPublicationCard
  QbAnalyticsHour: urn:jabiz:dataset:default:QbAnalyticsHour
  QbStatsRun: urn:jabiz:dataset:default:QbStatsRun
params:
  publicationId: { like: QbPublicationCard.publicationId, description: "Only this publication (none: all of the sponsor's)" }
  from:          { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The first bucket holds this time }
  to:            { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The last bucket holds the time just before this }
  bucket:        { kind: { type: text, maxLength: 4 }, required: true, description: "HOUR (UTC hours) or DAY (the deployment's days)" }
results:
  periodStart: { kind: { type: temporal, role: EVENT_TIME } }
  periodEnd:   { kind: { type: temporal, role: EVENT_TIME } }
  views:       { kind: { type: numeric, precision: 12, scale: 0 } }
  starts:      { kind: { type: numeric, precision: 12, scale: 0 } }
  completions: { kind: { type: numeric, precision: 12, scale: 0 } }
  passes:      { kind: { type: numeric, precision: 12, scale: 0 } }
  winners:     { kind: { type: numeric, precision: 12, scale: 0 } }
  spent:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  zone:        { from: QbStatsRun.zone }
permissions: [qb.stats.read]
---*/
-- One row: the trend's buckets (the same slots as qb.sponsor.analytics.trend: written the same way, change them together)
-- summed, from the first bucket's start to the last one's end. Only the sponsor's own publications (their dataset of
-- publication cards). Aggregates only (D-Q9e-4).
WITH tz AS (
    SELECT coalesce(max(r.{{QbStatsRun.zone}}), 'UTC') AS z
    FROM {{QbStatsRun}} r
    WHERE r.{{QbStatsRun.job}} = 'qb.analytics'),
slots AS (
    SELECT s AS slot_start, s + interval '1 hour' AS slot_end
    FROM generate_series(
        to_timestamp(floor(extract(epoch FROM CAST(:from AS timestamptz)) / 3600) * 3600),
        least(CAST(:to AS timestamptz) - interval '1 microsecond',
              to_timestamp(floor(extract(epoch FROM CAST(:from AS timestamptz)) / 3600) * 3600)
                  + interval '399 hours'),
        interval '1 hour') s
    WHERE CAST(:bucket AS text) = 'HOUR'
    UNION ALL
    SELECT d AT TIME ZONE tz.z AS slot_start, (d + interval '1 day') AT TIME ZONE tz.z AS slot_end
    FROM tz
    CROSS JOIN generate_series(
        date_trunc('day', CAST(:from AS timestamptz) AT TIME ZONE tz.z),
        least(date_trunc('day', (CAST(:to AS timestamptz) - interval '1 microsecond') AT TIME ZONE tz.z),
              date_trunc('day', CAST(:from AS timestamptz) AT TIME ZONE tz.z) + interval '399 days'),
        interval '1 day') d
    WHERE CAST(:bucket AS text) = 'DAY'),
bounds AS (
    SELECT min(slot_start) AS lo, max(slot_end) AS hi FROM slots),
mine AS (
    SELECT p.{{QbPublicationCard.publicationId}} AS pid, p.{{QbPublicationCard.sponsorId}} AS sponsor
    FROM {{QbPublicationCard}} p
    WHERE CAST(:publicationId AS uuid) IS NULL OR p.{{QbPublicationCard.publicationId}} = CAST(:publicationId AS uuid)),
hours AS (
    SELECT h.{{QbAnalyticsHour.views}} AS v_views, h.{{QbAnalyticsHour.starts}} AS v_starts,
           h.{{QbAnalyticsHour.completions}} AS v_completions, h.{{QbAnalyticsHour.passes}} AS v_passes,
           h.{{QbAnalyticsHour.winners}} AS v_winners, h.{{QbAnalyticsHour.spent}} AS v_spent
    FROM {{QbAnalyticsHour}} h
    JOIN mine m ON m.pid = h.{{QbAnalyticsHour.publicationId}}
    CROSS JOIN bounds b
    WHERE h.{{QbAnalyticsHour.sponsorId}} IN (SELECT m2.sponsor FROM mine m2)
      AND h.{{QbAnalyticsHour.hourStart}} >= b.lo AND h.{{QbAnalyticsHour.hourStart}} < b.hi),
totals AS (
    SELECT coalesce(sum(v_views), 0) AS t_views, coalesce(sum(v_starts), 0) AS t_starts,
           coalesce(sum(v_completions), 0) AS t_completions, coalesce(sum(v_passes), 0) AS t_passes,
           coalesce(sum(v_winners), 0) AS t_winners, coalesce(sum(v_spent), 0) AS t_spent
    FROM hours)
SELECT
    b.lo                                                          AS periodStart,
    b.hi                                                          AS periodEnd,
    CAST(t.t_views AS numeric(12, 0))                             AS views,
    CAST(t.t_starts AS numeric(12, 0))                            AS starts,
    CAST(t.t_completions AS numeric(12, 0))                       AS completions,
    CAST(t.t_passes AS numeric(12, 0))                            AS passes,
    CAST(t.t_winners AS numeric(12, 0))                           AS winners,
    t.t_spent                                                     AS spent,
    CAST(tz.z AS varchar(64))                                     AS zone
FROM bounds b
CROSS JOIN totals t
CROSS JOIN tz
