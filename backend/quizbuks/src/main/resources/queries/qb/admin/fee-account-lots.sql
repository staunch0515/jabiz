/*---
id: qb.admin.fee-account-lots
description: "F-M1: the lots of the account that pays the platform's fees with money left, one sponsor's, for finance - each grant of fee credit (until when it may be spent) and each sum of cash (when it arrived), in the order they are spent"
entities: [QbFeeCreditGrant, QbCashLot]
datasets:
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbCashLot: urn:jabiz:dataset:default:QbCashLot
params:
  sponsorId: { like: QbCashLot.sponsorId, required: true, description: "The sponsor (their user id)" }
results:
  subAccount: { kind: { type: text, maxLength: 10 } }
  lotId:      { kind: { type: text, maxLength: 36 } }
  source:     { kind: { type: text, maxLength: 20 } }
  amount:     { from: QbCashLot.amount }
  remaining:  { from: QbCashLot.remaining }
  arrivedAt:  { from: QbCashLot.arrivedAt }
  expiresAt:  { from: QbFeeCreditGrant.expiresAt }
  seq:        { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  filters: [subAccount]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [lotId]
permissions: [qb.admin.finance.read]
---*/
-- The order the account is spent in (fee.FeeAllocation): the fee credit first, the earliest to expire first (then the
-- earlier grant, then the id), then the cash in the order it arrived (then the id); seq numbers the lots in that order.
-- arrivedAt is when a grant was made or the cash arrived. A lot of fee credit past its expiry is not spent (F-M2's
-- daily job takes what it has left).
SELECT CAST(x.x_sub AS varchar(10)) AS subAccount, CAST(x.x_lot AS varchar(36)) AS lotId,
       CAST(x.x_source AS varchar(20)) AS source, x.x_amount AS amount, x.x_remaining AS remaining,
       x.x_arrived AS arrivedAt, x.x_expires AS expiresAt,
       row_number() OVER (ORDER BY x.x_part, x.x_expires, x.x_arrived, x.x_lot) AS seq
FROM (
    SELECT 1 AS x_part, 'FEE_CREDIT' AS x_sub, CAST(g.{{QbFeeCreditGrant.grantId}} AS text) AS x_lot,
           g.{{QbFeeCreditGrant.source}} AS x_source, g.{{QbFeeCreditGrant.amount}} AS x_amount,
           g.{{QbFeeCreditGrant.remaining}} AS x_remaining, g.{{QbFeeCreditGrant.grantedAt}} AS x_arrived,
           g.{{QbFeeCreditGrant.expiresAt}} AS x_expires
    FROM {{QbFeeCreditGrant}} g
    WHERE g.{{QbFeeCreditGrant.remaining}} > 0
  AND g.{{QbFeeCreditGrant.sponsorId}} = :sponsorId
    UNION ALL
    SELECT 2, 'CASH', CAST(l.{{QbCashLot.lotId}} AS text), l.{{QbCashLot.source}}, l.{{QbCashLot.amount}},
           l.{{QbCashLot.remaining}}, l.{{QbCashLot.arrivedAt}}, CAST(NULL AS timestamptz)
    FROM {{QbCashLot}} l
    WHERE l.{{QbCashLot.remaining}} > 0
  AND l.{{QbCashLot.sponsorId}} = :sponsorId
) x
WHERE true
