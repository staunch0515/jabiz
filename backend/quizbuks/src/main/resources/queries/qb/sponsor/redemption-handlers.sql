/*---
id: qb.sponsor.redemption-handlers
description: "Q74: who handed over the sponsor's prizes (the sponsor or one of their staff, Q39): how many and their value today, in the last 7 days, this month and in all (the deployment's days)"
entities: [QbRedemption, QbProfile, QbStatsRun]
datasets:
  QbRedemption: urn:jabiz:dataset:sponsor:QbRedemption
  QbProfile: urn:jabiz:dataset:default:QbProfile
  QbStatsRun: urn:jabiz:dataset:default:QbStatsRun
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The client's now (the server's clock): today is its day in the deployment's zone" }
results:
  handlerId:  { from: QbRedemption.redeemedBy }
  self:       { kind: { type: bool } }
  name:       { from: QbProfile.displayName }
  todayCount: { kind: { type: numeric, precision: 9, scale: 0 } }
  todayValue: { kind: { type: monetary, currency: JPY, scale: 0 } }
  weekCount:  { kind: { type: numeric, precision: 9, scale: 0 } }
  weekValue:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  monthCount: { kind: { type: numeric, precision: 9, scale: 0 } }
  monthValue: { kind: { type: monetary, currency: JPY, scale: 0 } }
  totalCount: { kind: { type: numeric, precision: 9, scale: 0 } }
  totalValue: { kind: { type: monetary, currency: JPY, scale: 0 } }
  zone:       { kind: { type: text, maxLength: 64 } }
list:
  sorts: [totalCount]
  defaultSort: { field: totalCount, asc: false }
  key: [handlerId]
permissions: [qb.publication.write]
---*/
-- Every one who ever handed over one of the sponsor's prizes, staff that left included (their counts stay theirs),
-- the sponsor themselves first; the bounds are qb.app.staff-handled's. Names are the profiles' display names.
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
    FROM run),
handed AS (
    SELECT r.{{QbRedemption.redeemedBy}} AS handler,
           bool_or(r.{{QbRedemption.redeemedBy}} = r.{{QbRedemption.publisherId}}) AS self,
           count(*) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= b.day_start) AS today_count,
           coalesce(sum(r.{{QbRedemption.amount}}) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= b.day_start), 0)
               AS today_value,
           count(*) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= b.week_start) AS week_count,
           coalesce(sum(r.{{QbRedemption.amount}}) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= b.week_start), 0)
               AS week_value,
           count(*) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= b.month_start) AS month_count,
           coalesce(sum(r.{{QbRedemption.amount}}) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= b.month_start), 0)
               AS month_value,
           count(*) AS total_count,
           coalesce(sum(r.{{QbRedemption.amount}}), 0) AS total_value,
           b.z AS z
    FROM {{QbRedemption}} r
    CROSS JOIN bounds b
    WHERE r.{{QbRedemption.status}} = 'REDEEMED' AND r.{{QbRedemption.redeemedBy}} IS NOT NULL
    GROUP BY r.{{QbRedemption.redeemedBy}}, b.z)
SELECT
    h.handler                     AS handlerId,
    h.self                        AS self,
    p.{{QbProfile.displayName}}   AS name,
    h.today_count                 AS todayCount,
    h.today_value                 AS todayValue,
    h.week_count                  AS weekCount,
    h.week_value                  AS weekValue,
    h.month_count                 AS monthCount,
    h.month_value                 AS monthValue,
    h.total_count                 AS totalCount,
    h.total_value                 AS totalValue,
    h.z                           AS zone
FROM handed h
LEFT JOIN {{QbProfile}} p ON p.{{QbProfile.userId}} = h.handler AND NOT h.self
