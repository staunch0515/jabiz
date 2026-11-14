/*---
id: qb.system.fee-credit-remind-due
description: The sponsors with a lot of platform fee Kudos that expires within qb.fee-credit.expiry-notice-days and of which they were not told (the items of the daily job qb.fee-credit-remind, F-M2), left in the account or (F-M9c) in the AI credit
entities: [QbFeeCreditGrant, QbPrepayment, QbFeePayment, SysParam]
datasets:
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
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
-- F-M9c: a grant bought into the AI credit is spent from the account (remaining 0) but expires in the credit (the AI
-- credit's daily job voids it), so what the open AI credit holds of it counts as left: its parts there, signed, less the
-- positive parts fees took of it (as qb.system.ai-credit-held). Only the grants due and not voided are looked at.
, due AS (
    SELECT g.{{QbFeeCreditGrant.grantId}} AS d_id, g.{{QbFeeCreditGrant.sponsorId}} AS d_sponsor,
           g.{{QbFeeCreditGrant.remaining}} AS d_left, g.{{QbFeeCreditGrant.voidedAt}} AS d_voided
    FROM {{QbFeeCreditGrant}} g
    CROSS JOIN params
    WHERE g.{{QbFeeCreditGrant.remindedAt}} IS NULL
      AND g.{{QbFeeCreditGrant.expiresAt}} > :at
      AND g.{{QbFeeCreditGrant.expiresAt}} <= CAST(:at AS timestamptz) + make_interval(days => params.days)
), credits AS (
    SELECT CAST(c.{{QbPrepayment.prepaymentId}} AS text) AS c_id
    FROM {{QbPrepayment}} c
    WHERE c.{{QbPrepayment.purpose}} = 'AI' AND c.{{QbPrepayment.status}} = 'OPEN'
      AND c.{{QbPrepayment.sponsorId}} IN (SELECT d_sponsor FROM due WHERE d_left = 0 AND d_voided IS NULL)
), held AS (
    SELECT p.{{QbFeePayment.grantId}} AS h_id, p.{{QbFeePayment.amount}} AS h_amount
    FROM {{QbFeePayment}} p JOIN credits c ON p.{{QbFeePayment.targetId}} = c.c_id
    WHERE p.{{QbFeePayment.grantId}} IN (SELECT d_id FROM due)
    UNION ALL
    SELECT u.{{QbFeePayment.grantId}}, -u.{{QbFeePayment.amount}}
    FROM {{QbFeePayment}} u JOIN credits c ON u.{{QbFeePayment.prepaymentId}} = c.c_id
    WHERE u.{{QbFeePayment.amount}} > 0 AND u.{{QbFeePayment.grantId}} IN (SELECT d_id FROM due)
)
SELECT DISTINCT d.d_sponsor AS sponsorId
FROM due d
WHERE d.d_left > 0
   OR (d.d_voided IS NULL
       AND (SELECT coalesce(sum(h.h_amount), 0) FROM held h WHERE h.h_id = d.d_id) > 0)
