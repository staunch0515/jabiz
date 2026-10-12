/*---
id: qb.sponsor.wallet
description: The sponsor's wallet (M-04) - the balance, the rewards waiting for a top-up, and what came in and went out
entities: [QbWallet, QbTopUp, LedgerEntry, LedgerAccount]
datasets:
  QbWallet: urn:jabiz:dataset:sponsor:QbWallet
  QbTopUp: urn:jabiz:dataset:sponsor:QbTopUp
  LedgerEntry: urn:jabiz:dataset:sponsor:LedgerEntry
results:
  balance:       { from: QbWallet.balance }
  held:          { from: QbWallet.held }
  pendingCount:  { from: QbWallet.pendingCount }
  pendingAmount: { from: QbWallet.pendingAmount }
  toppedUp:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  takenBack:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  topUpCount:    { kind: { type: numeric, precision: 9, scale: 0 } }
  spent:         { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.sponsor.finance.read]
---*/
-- Every dataset is the caller's own (their wallet, top-ups and ledger lines), so this is one row. The balance is the
-- wallet's cache, which the daily reconciliation compares with the ledger. Spent is the rewards paid: the lines of
-- account 2110 debited with a publication (refunds taken back carry none).
SELECT
    coalesce(w.w_balance, 0)        AS balance,
    coalesce(w.w_held, 0)           AS held,
    coalesce(w.w_pending_count, 0)  AS pendingCount,
    coalesce(w.w_pending_amount, 0) AS pendingAmount,
    coalesce(p.p_paid, 0)           AS toppedUp,
    coalesce(p.p_taken, 0)          AS takenBack,
    coalesce(p.p_count, 0)          AS topUpCount,
    coalesce(s.s_spent, 0)          AS spent
FROM (SELECT 1 AS one) base
LEFT JOIN (
    SELECT x.{{QbWallet.balance}} AS w_balance, x.{{QbWallet.held}} AS w_held,
           x.{{QbWallet.pendingCount}} AS w_pending_count,
           x.{{QbWallet.pendingAmount}} AS w_pending_amount
    FROM {{QbWallet}} x
    WHERE x.{{QbWallet.kind}} = 'SPONSOR') w ON true
CROSS JOIN (
    SELECT sum(t.{{QbTopUp.amount}}) AS p_paid,
           sum(t.{{QbTopUp.refundedAmount}} + t.{{QbTopUp.disputeClawback}}) AS p_taken,
           count(*) AS p_count
    FROM {{QbTopUp}} t
    WHERE t.{{QbTopUp.status}} = 'PAID') p
CROSS JOIN (
    SELECT sum(e.{{LedgerEntry.amount}}) AS s_spent
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2110'
      AND e.{{LedgerEntry.direction}} = 'DEBIT'
      AND e.{{LedgerEntry.dimension2}} IS NOT NULL) s
