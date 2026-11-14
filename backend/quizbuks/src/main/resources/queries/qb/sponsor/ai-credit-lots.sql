/*---
id: qb.sponsor.ai-credit-lots
description: "What the Sponsor's AI credit holds, lot by lot (F-M9b): granted credit by grant with its expiry (spent first, the earliest to expire first; one expired or voided is not used and is voided by the daily expiry), and cash"
entities: [QbPrepayment, QbFeePayment, QbFeeCreditGrant]
datasets:
  QbPrepayment: urn:jabiz:dataset:sponsor:QbPrepayment
  QbFeePayment: urn:jabiz:dataset:sponsor:QbFeePayment
  QbFeeCreditGrant: urn:jabiz:dataset:sponsor:QbFeeCreditGrant
results:
  subAccount: { kind: { type: text, maxLength: 20 } }
  lotId:      { kind: { type: text, maxLength: 36 } }
  amount:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  expiresAt:  { from: QbFeeCreditGrant.expiresAt }
  voidedAt:   { from: QbFeeCreditGrant.voidedAt }
list:
  sorts: [expiresAt]
  defaultSort: { field: expiresAt, asc: true }
  key: [subAccount, lotId]
permissions: [qb.sponsor.finance.read]
---*/
-- As qb.system.ai-credit-held reads one credit (its own parts, signed, less the positive parts fees took of it), for
-- the caller's open AI credit; the lots with something left, a sponsor's few.
WITH credit AS (
    SELECT CAST(p.{{QbPrepayment.prepaymentId}} AS text) AS c_id
    FROM {{QbPrepayment}} p
    WHERE p.{{QbPrepayment.purpose}} = 'AI' AND p.{{QbPrepayment.status}} = 'OPEN'
), parts AS (
    SELECT p.{{QbFeePayment.subAccount}} AS r_sub,
           coalesce(p.{{QbFeePayment.grantId}}, p.{{QbFeePayment.cashLotId}}) AS r_lot,
           p.{{QbFeePayment.amount}} AS r_amount
    FROM {{QbFeePayment}} p JOIN credit c ON p.{{QbFeePayment.targetId}} = c.c_id
    UNION ALL
    SELECT u.{{QbFeePayment.subAccount}}, coalesce(u.{{QbFeePayment.grantId}}, u.{{QbFeePayment.cashLotId}}),
           -u.{{QbFeePayment.amount}}
    FROM {{QbFeePayment}} u JOIN credit c ON u.{{QbFeePayment.prepaymentId}} = c.c_id
    WHERE u.{{QbFeePayment.amount}} > 0
)
SELECT CAST(h.r_sub AS varchar(20)) AS subAccount, CAST(h.r_lot AS varchar(36)) AS lotId,
       CAST(h.r_held AS numeric(19, 0)) AS amount, g.{{QbFeeCreditGrant.expiresAt}} AS expiresAt,
       g.{{QbFeeCreditGrant.voidedAt}} AS voidedAt
FROM (SELECT r_sub, r_lot, sum(r_amount) AS r_held FROM parts GROUP BY r_sub, r_lot) h
LEFT JOIN {{QbFeeCreditGrant}} g ON h.r_sub = 'FEE_CREDIT' AND g.{{QbFeeCreditGrant.grantId}} = h.r_lot
WHERE h.r_held > 0
