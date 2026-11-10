/*---
id: qb.admin.fee-account-totals
description: "F-M1: the accounts that pay the platform's fees in all, by sub-account - the fee credit (2190) and the cash (2160): what the wallets hold, what their lots have left, how many lots and sponsors have money left, and what paid fees in the month"
entities: [QbWallet, QbFeeCreditGrant, QbCashLot, QbFeePayment]
datasets:
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbCashLot: urn:jabiz:dataset:default:QbCashLot
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
params:
  from: { like: QbFeePayment.paidAt, required: true, description: "The month's first instant: payments at or after it count" }
  to:   { like: QbFeePayment.paidAt, required: true, description: "The next month's first instant: payments before it count" }
results:
  subAccount:    { kind: { type: text, maxLength: 10 } }
  balance:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  lotsRemaining: { kind: { type: monetary, currency: JPY, scale: 0 } }
  openLots:      { kind: { type: numeric, precision: 12, scale: 0 } }
  sponsors:      { kind: { type: numeric, precision: 12, scale: 0 } }
  paidFees:      { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts:   [subAccount]
  defaultSort: { field: subAccount, asc: false }
  key: [subAccount]
permissions: [qb.admin.finance.read]
---*/
-- Two rows, FEE_CREDIT and CASH, also when empty. balance is the wallets' caches and lotsRemaining what the lots have
-- left: the daily reconciliation's FEE_CREDIT and CASH_LOTS compare them by sponsor. paidFees is what the month's
-- payment parts took from the sub-account (less what voided invoices gave back), by when they were paid.
SELECT CAST(s.t_sub AS varchar(10)) AS subAccount,
       coalesce(w.t_balance, 0)   AS balance,
       coalesce(l.t_remaining, 0) AS lotsRemaining,
       coalesce(l.t_lots, 0)      AS openLots,
       coalesce(l.t_sponsors, 0)  AS sponsors,
       coalesce(p.t_paid, 0)      AS paidFees
FROM (VALUES ('FEE_CREDIT'), ('CASH')) AS s (t_sub)
LEFT JOIN (
    SELECT x.{{QbWallet.kind}} AS t_kind, sum(x.{{QbWallet.balance}}) AS t_balance
    FROM {{QbWallet}} x
    WHERE x.{{QbWallet.kind}} IN ('FEE_CREDIT', 'CASH')
    GROUP BY x.{{QbWallet.kind}}) w ON w.t_kind = s.t_sub
LEFT JOIN (
    SELECT 'FEE_CREDIT' AS t_sub, sum(g.{{QbFeeCreditGrant.remaining}}) AS t_remaining, count(*) AS t_lots,
           count(DISTINCT g.{{QbFeeCreditGrant.sponsorId}}) AS t_sponsors
    FROM {{QbFeeCreditGrant}} g
    WHERE g.{{QbFeeCreditGrant.remaining}} > 0
    UNION ALL
    SELECT 'CASH', sum(c.{{QbCashLot.remaining}}), count(*), count(DISTINCT c.{{QbCashLot.sponsorId}})
    FROM {{QbCashLot}} c
    WHERE c.{{QbCashLot.remaining}} > 0) l ON l.t_sub = s.t_sub
LEFT JOIN (
    SELECT y.{{QbFeePayment.subAccount}} AS t_sub, sum(y.{{QbFeePayment.amount}}) AS t_paid
    FROM {{QbFeePayment}} y
    WHERE y.{{QbFeePayment.paidAt}} >= :from
      AND y.{{QbFeePayment.paidAt}} < :to
    GROUP BY y.{{QbFeePayment.subAccount}}) p ON p.t_sub = s.t_sub
