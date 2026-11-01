/*---
id: qb.app.staff-handled-totals
description: "Q74: how many prizes the caller handed over at their sponsor's counter, and their value, today, in the last 7 days and this month (the deployment's days)"
entities: [QbSponsorStaff, QbRedemption, QbStatsRun]
datasets:
  QbSponsorStaff: urn:jabiz:dataset:taker:QbSponsorStaff
  QbRedemption: urn:jabiz:dataset:default:QbRedemption
  QbStatsRun: urn:jabiz:dataset:default:QbStatsRun
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The client's now (the server's clock): today is its day in the deployment's zone" }
results:
  todayCount: { kind: { type: numeric, precision: 9, scale: 0 } }
  todayValue: { kind: { type: monetary, currency: JPY, scale: 0 } }
  weekCount:  { kind: { type: numeric, precision: 9, scale: 0 } }
  weekValue:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  monthCount: { kind: { type: numeric, precision: 9, scale: 0 } }
  monthValue: { kind: { type: monetary, currency: JPY, scale: 0 } }
  zone:       { kind: { type: text, maxLength: 64 } }
permissions: [qb.me]
---*/
-- One row (none when the caller is on no staff): the bounds are qb.app.staff-handled's, through the caller's own place.
WITH run AS (
    SELECT coalesce(max(r.{{QbStatsRun.zone}}), 'UTC') AS z
    FROM {{QbStatsRun}} r
    WHERE r.{{QbStatsRun.job}} = 'qb.analytics'),
bounds AS (
    SELECT run.z AS z,
        date_trunc('day', CAST(:at AS timestamptz) AT TIME ZONE run.z) AT TIME ZONE run.z AS day_start,
        (date_trunc('day', CAST(:at AS timestamptz) AT TIME ZONE run.z) - interval '6 days') AT TIME ZONE run.z
            AS week_start,
        date_trunc('month', CAST(:at AS timestamptz) AT TIME ZONE run.z) AT TIME ZONE run.z AS month_start
    FROM run)
SELECT
    count(r.{{QbRedemption.redemptionId}}) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= b.day_start) AS todayCount,
    coalesce(sum(r.{{QbRedemption.amount}}) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= b.day_start), 0)
        AS todayValue,
    count(r.{{QbRedemption.redemptionId}}) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= b.week_start) AS weekCount,
    coalesce(sum(r.{{QbRedemption.amount}}) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= b.week_start), 0)
        AS weekValue,
    count(r.{{QbRedemption.redemptionId}}) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= b.month_start)
        AS monthCount,
    coalesce(sum(r.{{QbRedemption.amount}}) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= b.month_start), 0)
        AS monthValue,
    b.z AS zone
FROM {{QbSponsorStaff}} s
CROSS JOIN bounds b
LEFT JOIN {{QbRedemption}} r ON r.{{QbRedemption.publisherKind}} = 'SPONSOR'
                            AND r.{{QbRedemption.publisherId}} = s.{{QbSponsorStaff.sponsorId}}
                            AND r.{{QbRedemption.redeemedBy}} = s.{{QbSponsorStaff.userId}}
                            AND r.{{QbRedemption.status}} = 'REDEEMED'
GROUP BY b.z
