/*---
id: qb.sponsor.wallet
description: The sponsor's wallet (M-04) - the balance, the rewards waiting for a top-up, what came in and went out, (Q87a) the cash account and (F-M1) the fee credit
entities: [QbWallet, QbTopUp, LedgerEntry, LedgerAccount, QbFeeInvoice, QbFeeCharge, QbSponsor, SysParam]
datasets:
  QbWallet: urn:jabiz:dataset:sponsor:QbWallet
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
  QbFeeCharge: urn:jabiz:dataset:sponsor:QbFeeCharge
  QbSponsor: urn:jabiz:dataset:sponsor:QbSponsor
  SysParam: urn:jabiz:dataset:now:SysParam
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
  cashBalance:   { kind: { type: monetary, currency: JPY, scale: 0 } }
  cashHeld:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  unpaidTotal:   { kind: { type: monetary, currency: JPY, scale: 0 } }
  unpaidCount:   { kind: { type: numeric, precision: 9, scale: 0 } }
  overdueCount:  { kind: { type: numeric, precision: 9, scale: 0 } }
  billingMode:   { kind: { type: text, maxLength: 10 } }
  creditLimit:   { kind: { type: monetary, currency: JPY, scale: 0 } }
  uninvoicedTotal: { kind: { type: monetary, currency: JPY, scale: 0 } }
  entryFeesUnsettled: { kind: { type: monetary, currency: JPY, scale: 0 } }
  feeCreditBalance: { kind: { type: monetary, currency: JPY, scale: 0 } }
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
    coalesce(s.s_spent, 0)          AS spent,
    coalesce(c.c_balance, 0)        AS cashBalance,
    coalesce(c.c_held, 0)           AS cashHeld,
    coalesce(f.f_total, 0)          AS unpaidTotal,
    coalesce(f.f_count, 0)          AS unpaidCount,
    coalesce(f.f_overdue, 0)        AS overdueCount,
    CAST(coalesce(sp.sp_mode, d.d_mode, 'PREPAID') AS varchar(10)) AS billingMode,
    coalesce(sp.sp_limit, 0)        AS creditLimit,
    coalesce(u.u_total, 0)          AS uninvoicedTotal,
    coalesce(ef.ef_total, 0)        AS entryFeesUnsettled,
    coalesce(fc.fc_balance, 0)      AS feeCreditBalance
FROM (SELECT 1 AS one) base
-- Q88b: the entry fees takers paid for the sponsor's quizzes, not yet moved to the cash account by the daily job
-- (2170 for the sponsor, credit less debit; the sponsor's ledger view holds their lines).
CROSS JOIN (
    SELECT sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS ef_total
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2170') ef
LEFT JOIN (
    SELECT x.{{QbWallet.balance}} AS w_balance, x.{{QbWallet.held}} AS w_held,
           x.{{QbWallet.pendingCount}} AS w_pending_count,
           x.{{QbWallet.pendingAmount}} AS w_pending_amount
    FROM {{QbWallet}} x
    WHERE x.{{QbWallet.kind}} = 'SPONSOR') w ON true
-- Q87a: the cash account, the second row of the same owner.
LEFT JOIN (
    SELECT x.{{QbWallet.balance}} AS c_balance, x.{{QbWallet.held}} AS c_held
    FROM {{QbWallet}} x
    WHERE x.{{QbWallet.kind}} = 'CASH') c ON true
-- F-M1: the fee credit (platform fee Kudos), the third row of the same owner.
LEFT JOIN (
    SELECT x.{{QbWallet.balance}} AS fc_balance
    FROM {{QbWallet}} x
    WHERE x.{{QbWallet.kind}} = 'FEE_CREDIT') fc ON true
-- Q87b: the platform fees still owed (F-M1: less what is paid of them), and the billing mode in effect (the sponsor's own, else the platform's default).
CROSS JOIN (
    SELECT sum(i.{{QbFeeInvoice.total}} - i.{{QbFeeInvoice.paidAmount}}) AS f_total, count(*) AS f_count,
           count(*) FILTER (WHERE i.{{QbFeeInvoice.status}} = 'OVERDUE') AS f_overdue
    FROM {{QbFeeInvoice}} i
    WHERE i.{{QbFeeInvoice.status}} IN ('OPEN', 'PARTIAL', 'OVERDUE')) f
-- Q87b: the postpaid fees not on an invoice yet (the month's, or one of their own when paid early).
CROSS JOIN (
    SELECT sum(c.{{QbFeeCharge.total}}) AS u_total
    FROM {{QbFeeCharge}} c
    WHERE c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
      AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} v
                      WHERE v.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
                         OR (v.{{QbFeeInvoice.chargeId}} IS NULL
                             AND v.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}}))) u
LEFT JOIN (
    SELECT z.{{QbSponsor.billingMode}} AS sp_mode, z.{{QbSponsor.creditLimit}} AS sp_limit
    FROM {{QbSponsor}} z) sp ON true
LEFT JOIN (
    SELECT CASE WHEN p.{{SysParam.value}} IN ('PREPAID', 'POSTPAID') THEN p.{{SysParam.value}} END AS d_mode
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.fee.default-billing-mode') d ON true
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
