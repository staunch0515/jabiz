/*---
id: qb.system.fee-credit-remind-due
description: The sponsors with a lot of platform fee Kudos that expires within qb.fee-credit.expiry-notice-days and of which they were not told (the items of the daily job qb.fee-credit-remind, F-M2)
entities: [QbFeeCreditGrant, SysParam]
datasets:
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at: { like: QbFeeCreditGrant.expiresAt, required: true, description: "The run's time" }
results:
  sponsorId: { from: QbFeeCreditGrant.sponsorId }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.fee.system]
---*/
-- The notice in days (1 to 365; anything else or none: 14, the setup's value). The item decides again under the sponsor's
-- wallet lock, with the parameter as it is then, and sends at most one mail in 24 hours.
WITH params AS (
    SELECT coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,3}$'
                                  AND CAST(p.{{SysParam.value}} AS int) BETWEEN 1 AND 365
                             THEN CAST(p.{{SysParam.value}} AS int) END), 14) AS days
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.fee-credit.expiry-notice-days')
SELECT DISTINCT g.{{QbFeeCreditGrant.sponsorId}} AS sponsorId
FROM {{QbFeeCreditGrant}} g
CROSS JOIN params
WHERE g.{{QbFeeCreditGrant.remaining}} > 0
  AND g.{{QbFeeCreditGrant.remindedAt}} IS NULL
  AND g.{{QbFeeCreditGrant.expiresAt}} > :at
  AND g.{{QbFeeCreditGrant.expiresAt}} <= CAST(:at AS timestamptz) + make_interval(days => params.days)
