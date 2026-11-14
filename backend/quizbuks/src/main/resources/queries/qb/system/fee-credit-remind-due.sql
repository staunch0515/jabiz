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
-- credit's daily job voids it), so what an open AI credit holds of it counts as left: its parts there, signed, less the
-- positive parts fees took of it (as qb.system.ai-credit-held), summed once by credit and grant (#718 review H1: no
-- subquery per grant). Each branch reads by an index: the account's lots by qb_fee_credit_grant_expiry_idx (remaining
-- > 0); the AI credits by qb_prepayment_ai_open_idx, their parts by target_id and prepayment_id, their grants by key.
-- A grant voided before it expired is as good as expired and not told of.
, account AS (
    SELECT g.{{QbFeeCreditGrant.sponsorId}} AS a_sponsor
    FROM {{QbFeeCreditGrant}} g
    CROSS JOIN params
    WHERE g.{{QbFeeCreditGrant.remaining}} > 0
      AND g.{{QbFeeCreditGrant.remindedAt}} IS NULL
      AND g.{{QbFeeCreditGrant.expiresAt}} > :at
      AND g.{{QbFeeCreditGrant.expiresAt}} <= CAST(:at AS timestamptz) + make_interval(days => params.days)
), credits AS (
    SELECT CAST(c.{{QbPrepayment.prepaymentId}} AS text) AS c_id, c.{{QbPrepayment.sponsorId}} AS c_sponsor
    FROM {{QbPrepayment}} c
    WHERE c.{{QbPrepayment.purpose}} = 'AI' AND c.{{QbPrepayment.status}} = 'OPEN' AND c.{{QbPrepayment.remaining}} > 0
), held AS (
    SELECT c.c_sponsor AS h_sponsor, p.{{QbFeePayment.grantId}} AS h_id, p.{{QbFeePayment.amount}} AS h_amount
    FROM credits c JOIN {{QbFeePayment}} p ON p.{{QbFeePayment.targetId}} = c.c_id
    WHERE p.{{QbFeePayment.grantId}} IS NOT NULL
    UNION ALL
    SELECT c.c_sponsor, u.{{QbFeePayment.grantId}}, -u.{{QbFeePayment.amount}}
    FROM credits c JOIN {{QbFeePayment}} u ON u.{{QbFeePayment.prepaymentId}} = c.c_id
    WHERE u.{{QbFeePayment.amount}} > 0 AND u.{{QbFeePayment.grantId}} IS NOT NULL
), in_credit AS (
    SELECT h.h_sponsor AS i_sponsor
    FROM held h
    JOIN {{QbFeeCreditGrant}} g ON g.{{QbFeeCreditGrant.grantId}} = h.h_id
    CROSS JOIN params
    WHERE g.{{QbFeeCreditGrant.remindedAt}} IS NULL
      AND g.{{QbFeeCreditGrant.voidedAt}} IS NULL
      AND g.{{QbFeeCreditGrant.expiresAt}} > :at
      AND g.{{QbFeeCreditGrant.expiresAt}} <= CAST(:at AS timestamptz) + make_interval(days => params.days)
    GROUP BY h.h_sponsor, h.h_id
    HAVING sum(h.h_amount) > 0
)
SELECT a_sponsor AS sponsorId FROM account
UNION
SELECT i_sponsor FROM in_credit
