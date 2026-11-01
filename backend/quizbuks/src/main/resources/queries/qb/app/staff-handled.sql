/*---
id: qb.app.staff-handled
description: "Q74: the prizes the caller handed over at the counter of the sponsor whose staff they are on, newest first, in a period of the deployment's zone"
entities: [QbSponsorStaff, QbRedemption, QbPublicationCard, QbProfile, QbStatsRun]
datasets:
  QbSponsorStaff: urn:jabiz:dataset:taker:QbSponsorStaff
  QbRedemption: urn:jabiz:dataset:default:QbRedemption
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbProfile: urn:jabiz:dataset:default:QbProfile
  QbStatsRun: urn:jabiz:dataset:default:QbStatsRun
params:
  at:     { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The client's now (the server's clock): the period's days are its days in the deployment's zone" }
  period: { kind: { type: text, maxLength: 5 }, description: "TODAY, WEEK (the last 7 days with today), MONTH (this month) or ALL (when not given)" }
results:
  redemptionId:    { from: QbRedemption.redemptionId }
  code:            { from: QbRedemption.code }
  publicationName: { from: QbPublicationCard.name }
  takerName:       { from: QbProfile.displayName }
  description:     { from: QbRedemption.description }
  amount:          { from: QbRedemption.amount }
  redeemedAt:      { from: QbRedemption.redeemedAt }
list:
  filters: [redeemedAt]
  sorts:   [redeemedAt]
  defaultSort: { field: redeemedAt, asc: false }
  key: [redemptionId]
permissions: [qb.me]
---*/
-- As the counter (qb.app.staff-redemptions, D-Q39-3): through the caller's own place on a staff only, so a former
-- staff member sees nothing of that sponsor any more (D-Q74-1), and only what they handed over themselves. The days are
-- the deployment's (jabiz.fiscal-year-zone, as the analytics job records it), the same bounds as
-- qb.app.staff-handled-totals.
WITH run AS (
    SELECT coalesce(max(r.{{QbStatsRun.zone}}), 'UTC') AS z
    FROM {{QbStatsRun}} r
    WHERE r.{{QbStatsRun.job}} = 'qb.analytics'),
bounds AS (
    SELECT CASE coalesce(CAST(:period AS text), 'ALL')
        WHEN 'TODAY' THEN date_trunc('day', CAST(:at AS timestamptz) AT TIME ZONE run.z) AT TIME ZONE run.z
        WHEN 'WEEK' THEN (date_trunc('day', CAST(:at AS timestamptz) AT TIME ZONE run.z) - interval '6 days')
            AT TIME ZONE run.z
        WHEN 'MONTH' THEN date_trunc('month', CAST(:at AS timestamptz) AT TIME ZONE run.z) AT TIME ZONE run.z
        END AS since
    FROM run)
SELECT
    r.{{QbRedemption.redemptionId}}  AS redemptionId,
    r.{{QbRedemption.code}}          AS code,
    c.{{QbPublicationCard.name}}     AS publicationName,
    p.{{QbProfile.displayName}}      AS takerName,
    r.{{QbRedemption.description}}   AS description,
    r.{{QbRedemption.amount}}        AS amount,
    r.{{QbRedemption.redeemedAt}}    AS redeemedAt
FROM {{QbSponsorStaff}} s
CROSS JOIN bounds b
JOIN {{QbRedemption}} r ON r.{{QbRedemption.publisherKind}} = 'SPONSOR'
                       AND r.{{QbRedemption.publisherId}} = s.{{QbSponsorStaff.sponsorId}}
                       AND r.{{QbRedemption.redeemedBy}} = s.{{QbSponsorStaff.userId}}
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = r.{{QbRedemption.publicationId}}
LEFT JOIN {{QbProfile}} p ON p.{{QbProfile.userId}} = r.{{QbRedemption.userId}}
WHERE r.{{QbRedemption.status}} = 'REDEEMED'
  AND (b.since IS NULL OR r.{{QbRedemption.redeemedAt}} >= b.since)
