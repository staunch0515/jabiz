/*---
id: qb.sponsor.analytics.trend
description: >-
  The sponsor's figures by hour or by day (M-11, M-43) - first views, starts, completions, passes, winners and Kudos
  spent of one of their publications or of all of them, every bucket of the period present (zeros included)
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
  bucketStart: { kind: { type: temporal, role: EVENT_TIME } }
  day:         { kind: { type: date } }
  views:       { kind: { type: numeric, precision: 12, scale: 0 } }
  starts:      { kind: { type: numeric, precision: 12, scale: 0 } }
  completions: { kind: { type: numeric, precision: 12, scale: 0 } }
  passes:      { kind: { type: numeric, precision: 12, scale: 0 } }
  winners:     { kind: { type: numeric, precision: 12, scale: 0 } }
  spent:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  gap:         { kind: { type: bool } }
list:
  sorts: [bucketStart]
  defaultSort: { field: bucketStart, asc: true }
  key: [bucketStart]
permissions: [qb.stats.read]
---*/
-- Only the sponsor's own publications (the sponsor's dataset of publication cards): another sponsor's publicationId finds
-- nothing. The buckets are found by the sponsor's index and joined to those publications. Aggregates only, no person (D-Q9e-4): counts are not held back by the minimum group (E-4). The hourly
-- buckets are the job's (every ten minutes; the hour now is filled as the job runs). HOUR: the UTC hours from the hour
-- of "from"; DAY: the days of the deployment's zone (jabiz.fiscal-year-zone, recorded by the job: D-Q9e-3) from the day
-- of "from". At most 400 buckets; the hours are whole, so a zone of whole hours divides them exactly. day is the
-- zone's date of the bucket's start (for HOUR as well). gap: a bucket holds increments of a time the job did not run.
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
    SELECT h.{{QbAnalyticsHour.hourStart}} AS v_hour, h.{{QbAnalyticsHour.views}} AS v_views,
           h.{{QbAnalyticsHour.starts}} AS v_starts, h.{{QbAnalyticsHour.completions}} AS v_completions,
           h.{{QbAnalyticsHour.passes}} AS v_passes, h.{{QbAnalyticsHour.winners}} AS v_winners,
           h.{{QbAnalyticsHour.spent}} AS v_spent, h.{{QbAnalyticsHour.gap}} AS v_gap
    FROM {{QbAnalyticsHour}} h
    JOIN mine m ON m.pid = h.{{QbAnalyticsHour.publicationId}}
    CROSS JOIN bounds b
    WHERE h.{{QbAnalyticsHour.sponsorId}} IN (SELECT m2.sponsor FROM mine m2)
      AND h.{{QbAnalyticsHour.hourStart}} >= b.lo AND h.{{QbAnalyticsHour.hourStart}} < b.hi)
SELECT
    s.slot_start                                                  AS bucketStart,
    CAST(s.slot_start AT TIME ZONE tz.z AS date)                  AS day,
    CAST(coalesce(sum(h.v_views), 0) AS numeric(12, 0))           AS views,
    CAST(coalesce(sum(h.v_starts), 0) AS numeric(12, 0))          AS starts,
    CAST(coalesce(sum(h.v_completions), 0) AS numeric(12, 0))     AS completions,
    CAST(coalesce(sum(h.v_passes), 0) AS numeric(12, 0))          AS passes,
    CAST(coalesce(sum(h.v_winners), 0) AS numeric(12, 0))         AS winners,
    coalesce(sum(h.v_spent), 0)                                   AS spent,
    coalesce(bool_or(h.v_gap), false)                             AS gap
FROM slots s
CROSS JOIN tz
LEFT JOIN hours h ON h.v_hour >= s.slot_start AND h.v_hour < s.slot_end
GROUP BY s.slot_start, tz.z
