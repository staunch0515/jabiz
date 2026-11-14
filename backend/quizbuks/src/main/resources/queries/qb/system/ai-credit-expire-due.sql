/*---
id: qb.system.ai-credit-expire-due
description: The sponsors whose AI credit holds fee credit expired by the run's time (the items of the daily job qb.ai-credit-expire, F-M9a-2)
entities: [QbPrepayment, QbFeePayment, QbFeeCreditGrant]
datasets:
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
params:
  at: { like: QbFeeCreditGrant.expiresAt, required: true, description: "The run's time" }
results:
  itemKey:   { kind: { type: text, maxLength: 36 } }
  sponsorId: { kind: { type: text, maxLength: 128 } }
list:
  sorts: [itemKey]
  defaultSort: { field: itemKey, asc: true }
  key: [itemKey]
permissions: [qb.fee.system]
---*/
-- What each open AI credit holds of each grant, as qb.system.ai-credit-held reads one credit (its own parts, signed,
-- less the positive parts fees took of it), kept where the grant expired by :at and something is left. A sponsor whose
-- open jobs hold all of it is listed every day until the month's charge spends it; the item then voids nothing. The
-- item reads everything again under the sponsor's AI and wallet locks. The key: the sponsor's id hashed (36 at most).
WITH credits AS (
    SELECT c.{{QbPrepayment.prepaymentId}} AS c_id, c.{{QbPrepayment.sponsorId}} AS c_sponsor
    FROM {{QbPrepayment}} c
    WHERE c.{{QbPrepayment.purpose}} = 'AI' AND c.{{QbPrepayment.status}} = 'OPEN' AND c.{{QbPrepayment.remaining}} > 0
), parts AS (
    SELECT c.c_sponsor AS r_sponsor, p.{{QbFeePayment.grantId}} AS r_grant, p.{{QbFeePayment.amount}} AS r_amount
    FROM credits c JOIN {{QbFeePayment}} p ON p.{{QbFeePayment.targetId}} = CAST(c.c_id AS text)
    WHERE p.{{QbFeePayment.grantId}} IS NOT NULL
    UNION ALL
    SELECT c.c_sponsor, u.{{QbFeePayment.grantId}}, -u.{{QbFeePayment.amount}}
    FROM credits c JOIN {{QbFeePayment}} u ON u.{{QbFeePayment.prepaymentId}} = CAST(c.c_id AS text)
    WHERE u.{{QbFeePayment.grantId}} IS NOT NULL AND u.{{QbFeePayment.amount}} > 0
)
SELECT DISTINCT CAST(md5(h.r_sponsor) AS varchar(36)) AS itemKey, CAST(h.r_sponsor AS varchar(128)) AS sponsorId
FROM (SELECT r_sponsor, r_grant, sum(r_amount) AS r_held FROM parts GROUP BY r_sponsor, r_grant) h
JOIN {{QbFeeCreditGrant}} g ON g.{{QbFeeCreditGrant.grantId}} = h.r_grant
WHERE h.r_held > 0 AND g.{{QbFeeCreditGrant.expiresAt}} <= :at
