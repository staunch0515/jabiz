/*---
id: qb.system.ai-credit-held
description: What a sponsor's AI credit still holds, lot by lot (F-M9, plan H4) - its own parts (positive set aside, negative given back or voided) less what fees took of each lot, summed in SQL so that the rows read are the lots with something left, never the parts (the credit is never closed and gains parts at every purchase, charge, return and expiry)
entities: [QbFeePayment, QbFeeCreditGrant]
datasets:
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
params:
  prepaymentId: { like: QbFeePayment.prepaymentId, required: true, description: The AI credit (a prepayment of the purpose AI) }
results:
  subAccount: { kind: { type: text, maxLength: 20 } }
  lotId:      { kind: { type: text, maxLength: 36 } }
  amount:     { kind: { type: numeric, precision: 19, scale: 0 } }
  expiresAt:  { from: QbFeeCreditGrant.expiresAt }
  voidedAt:   { from: QbFeeCreditGrant.voidedAt }
list:
  sorts: [lotId]
  defaultSort: { field: lotId, asc: true }
  key: [subAccount, lotId]
permissions: [qb.fee.system]
---*/
-- The same as Prepayments.held, in one statement: the parts whose object is the credit (all of them, signed) and the
-- positive parts fees took of it (a voided invoice's negative parts name it too, but give back to the account, not to
-- the credit, H1 of the F-M5 review). The lot is the grant for fee credit, the cash lot for cash. The caller orders the
-- lots as the account spends them (fee credit first, the earliest to expire first, then cash). A grant voided before it
-- expired (voidedAt) is as good as expired (#699 re-review M1).
WITH parts AS (
    SELECT p.{{QbFeePayment.subAccount}} AS r_sub,
           coalesce(p.{{QbFeePayment.grantId}}, p.{{QbFeePayment.cashLotId}}) AS r_lot,
           p.{{QbFeePayment.amount}} AS r_amount
    FROM {{QbFeePayment}} p
    WHERE p.{{QbFeePayment.targetId}} = :prepaymentId
    UNION ALL
    SELECT u.{{QbFeePayment.subAccount}}, coalesce(u.{{QbFeePayment.grantId}}, u.{{QbFeePayment.cashLotId}}),
           -u.{{QbFeePayment.amount}}
    FROM {{QbFeePayment}} u
    WHERE u.{{QbFeePayment.prepaymentId}} = :prepaymentId AND u.{{QbFeePayment.amount}} > 0
)
SELECT CAST(h.r_sub AS varchar(20)) AS subAccount, CAST(h.r_lot AS varchar(36)) AS lotId,
       CAST(h.r_held AS numeric(19, 0)) AS amount, g.{{QbFeeCreditGrant.expiresAt}} AS expiresAt,
       g.{{QbFeeCreditGrant.voidedAt}} AS voidedAt
FROM (SELECT r_sub, r_lot, sum(r_amount) AS r_held FROM parts GROUP BY r_sub, r_lot) h
LEFT JOIN {{QbFeeCreditGrant}} g ON h.r_sub = 'FEE_CREDIT' AND g.{{QbFeeCreditGrant.grantId}} = h.r_lot
WHERE h.r_held > 0
