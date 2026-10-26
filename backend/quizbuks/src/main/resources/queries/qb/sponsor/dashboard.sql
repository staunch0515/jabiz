/*---
id: qb.sponsor.dashboard
description: >-
  The sponsor's key figures (M-10) - publications running, paused and short of funds, takers answering now, Kudos
  spent today, the wallet's available balance and pending rewards, the budget left of the running publications with a
  cap, and when the analytics last ran
entities: [QbPublicationCard, QbPublicationCounter, QbPublicationStartCounter, QbAnalyticsCursor, QbAnalyticsHour, QbWallet, QbStatsRun]
datasets:
  QbPublicationCard: urn:jabiz:dataset:sponsor:QbPublicationCard
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  QbAnalyticsCursor: urn:jabiz:dataset:default:QbAnalyticsCursor
  QbAnalyticsHour: urn:jabiz:dataset:default:QbAnalyticsHour
  QbWallet: urn:jabiz:dataset:sponsor:QbWallet
  QbStatsRun: urn:jabiz:dataset:default:QbStatsRun
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The client's now: today is its day in the deployment's zone" }
results:
  live:            { kind: { type: numeric, precision: 9, scale: 0 } }
  paused:          { kind: { type: numeric, precision: 9, scale: 0 } }
  fundsShort:      { kind: { type: numeric, precision: 9, scale: 0 } }
  answering:       { kind: { type: numeric, precision: 12, scale: 0 } }
  spentToday:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  available:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  pendingCount:    { from: QbWallet.pendingCount }
  pendingAmount:   { from: QbWallet.pendingAmount }
  budgetRemaining: { kind: { type: monetary, currency: JPY, scale: 0 } }
  dayStart:        { kind: { type: temporal, role: EVENT_TIME } }
  zone:            { from: QbStatsRun.zone }
  refreshedAt:     { from: QbStatsRun.refreshedAt }
permissions: [qb.stats.read]
---*/
-- One row over the sponsor's own publications and wallet (their datasets). Live (E-3): the publications' states, the
-- takers answering now (starts - completions, both sides of the counters in this one statement), the wallet, and the
-- part of today's spending since the job's last run (the counter's spent less the cursor's; a publication the job has
-- not seen yet counts only when it went on today, review L1: the rest of its spending is not today's, and the job
-- puts its history in the hour it went on). The rest of today's
-- spending is the hourly buckets from the start of today in the deployment's zone (jabiz.fiscal-year-zone, recorded by
-- the job: D-Q9e-3). The budget left of a running publication with a cap is the cap less what it spent and what its
-- attempts in progress hold (reservedTotal - released), never below 0.
WITH run AS (
    SELECT max(r.{{QbStatsRun.refreshedAt}}) AS refreshed, coalesce(max(r.{{QbStatsRun.zone}}), 'UTC') AS z
    FROM {{QbStatsRun}} r
    WHERE r.{{QbStatsRun.job}} = 'qb.analytics'),
today AS (
    SELECT (date_trunc('day', CAST(:at AS timestamptz) AT TIME ZONE run.z) AT TIME ZONE run.z) AS day_start
    FROM run),
mine AS (
    SELECT p.{{QbPublicationCard.publicationId}} AS pid, p.{{QbPublicationCard.publisherId}} AS sponsor,
           p.{{QbPublicationCard.status}} AS v_status, p.{{QbPublicationCard.budgetCap}} AS cap,
           p.{{QbPublicationCard.wentOnAt}} AS went_on
    FROM {{QbPublicationCard}} p),
live AS (
    SELECT
        count(*) FILTER (WHERE m.v_status = 'LIVE')        AS live,
        count(*) FILTER (WHERE m.v_status = 'PAUSED')      AS paused,
        count(*) FILTER (WHERE m.v_status = 'FUNDS_SHORT') AS funds_short,
        coalesce(sum(coalesce(s.{{QbPublicationStartCounter.starts}}, 0)
                     - coalesce(n.{{QbPublicationCounter.completions}}, 0)), 0) AS answering,
        coalesce(sum(CASE
            WHEN c.{{QbAnalyticsCursor.publicationId}} IS NOT NULL
                THEN coalesce(n.{{QbPublicationCounter.spent}}, 0) - c.{{QbAnalyticsCursor.spent}}
            WHEN m.went_on >= t.day_start THEN coalesce(n.{{QbPublicationCounter.spent}}, 0)
            ELSE 0 END), 0) AS since_run,
        coalesce(sum(greatest(m.cap - coalesce(n.{{QbPublicationCounter.spent}}, 0)
                     - (coalesce(s.{{QbPublicationStartCounter.reservedTotal}}, 0)
                        - coalesce(n.{{QbPublicationCounter.released}}, 0)), 0))
                 FILTER (WHERE m.v_status = 'LIVE' AND m.cap IS NOT NULL), 0) AS budget_left
    FROM mine m
    LEFT JOIN {{QbPublicationCounter}} n ON n.{{QbPublicationCounter.publicationId}} = m.pid
    LEFT JOIN {{QbPublicationStartCounter}} s ON s.{{QbPublicationStartCounter.publicationId}} = m.pid
    LEFT JOIN {{QbAnalyticsCursor}} c ON c.{{QbAnalyticsCursor.publicationId}} = m.pid
    CROSS JOIN today t),
bucketed AS (
    SELECT coalesce(sum(h.{{QbAnalyticsHour.spent}}), 0) AS v_spent
    FROM {{QbAnalyticsHour}} h
    CROSS JOIN today t
    WHERE h.{{QbAnalyticsHour.publisherId}} IN (SELECT m.sponsor FROM mine m)
      AND h.{{QbAnalyticsHour.publicationId}} IN (SELECT m.pid FROM mine m)
      AND h.{{QbAnalyticsHour.hourStart}} >= t.day_start
      AND h.{{QbAnalyticsHour.hourStart}} <= CAST(:at AS timestamptz)),
wallet AS (
    SELECT max(w.{{QbWallet.balance}} - w.{{QbWallet.held}}) AS available,
           max(w.{{QbWallet.pendingCount}}) AS v_pending_count, max(w.{{QbWallet.pendingAmount}}) AS v_pending_amount
    FROM {{QbWallet}} w
    WHERE w.{{QbWallet.kind}} = 'SPONSOR')
SELECT
    CAST(l.live AS numeric(9, 0))                                 AS live,
    CAST(l.paused AS numeric(9, 0))                               AS paused,
    CAST(l.funds_short AS numeric(9, 0))                          AS fundsShort,
    CAST(l.answering AS numeric(12, 0))                           AS answering,
    b.v_spent + l.since_run                                       AS spentToday,
    coalesce(w.available, 0)                                      AS available,
    coalesce(w.v_pending_count, 0)                                AS pendingCount,
    coalesce(w.v_pending_amount, 0)                               AS pendingAmount,
    l.budget_left                                                 AS budgetRemaining,
    t.day_start                                                   AS dayStart,
    CAST(r.z AS varchar(64))                                      AS zone,
    r.refreshed                                                   AS refreshedAt
FROM live l
CROSS JOIN bucketed b
CROSS JOIN wallet w
CROSS JOIN today t
CROSS JOIN run r
