/*---
id: qb.admin.monthly
description: >-
  The month's transactions by kind (F-08): how many and how much, and what they moved on each account (debit less
  credit); from the ledger alone, by booking time, as recorded up to a time (if given)
entities: [LedgerAccount, LedgerTransaction, LedgerEntry]
params:
  from:    { like: LedgerTransaction.bookingTime, required: true, description: "The month's first instant: transactions booked at or after it count" }
  to:      { like: LedgerTransaction.bookingTime, required: true, description: "The next month's first instant: transactions booked before it count" }
  knownAt: { like: LedgerTransaction.bookingTime, description: "if given, the ledger (accounts included) as recorded at this time" }
results:
  seq:              { kind: { type: numeric, precision: 3, scale: 0 } }
  category:         { kind: { type: text, maxLength: 30 } }
  transactionCount: { kind: { type: numeric, precision: 12, scale: 0 } }
  amount:           { kind: { type: monetary, currency: JPY, scale: 0 } }
  stripeBalance:    { kind: { type: monetary, currency: JPY, scale: 0 } }
  bank:             { kind: { type: monetary, currency: JPY, scale: 0 } }
  sponsorWallets:   { kind: { type: monetary, currency: JPY, scale: 0 } }
  takerWallets:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  inTransit:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  revenue:          { kind: { type: monetary, currency: JPY, scale: 0 } }
  paymentFees:      { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  filters: [category]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [category]
permissions: [qb.admin.finance.read]
# As the platform's ledger reports (docs/design/19-reports.md section 2.2): knownAt reads the ledger as recorded then,
# the month is a condition on the booking time only.
timeSlice: { knownAt: knownAt }
report:
  period: { from: from, to: to }
  landscape: true
---*/
-- Every kind is a row, in a fixed order, also when the month has none of it. A transaction's kind follows from where it
-- was posted from (its source document) and what it moved: QuizBuks posts each kind from one place (QbLedger and the
-- wallet processes; AdminFinanceIT checks every kind). A refund and a lost dispute move the same accounts and differ
-- only in the description their processes give. A reversal keeps its original's source: a transfer's is the failed
-- sending, a payout's the failed payout; any other kind of posting is OTHER (the development credit among them).
-- amount is what the transactions moved (the sum of their debits); the account columns are each account's debit less
-- credit, as in jabiz.ledger.account_balances (an asset grows positive, a liability or revenue negative), so that a
-- row adds up to 0 and a column to the account's movement in the month (qb.admin.balances: debit less credit).
-- The month is by booking time: a reward counts when it was posted, not when its attempt was submitted.
WITH tx AS (
    SELECT t.{{LedgerTransaction.transactionId}}         AS tx_key,
           t.{{LedgerTransaction.sourceEntity}}          AS src,
           t.{{LedgerTransaction.description}}           AS descr,
           t.{{LedgerTransaction.reversesTransactionId}} AS reverses
    FROM {{LedgerTransaction}} t
    WHERE t.{{LedgerTransaction.bookingTime}} >= :from
      AND t.{{LedgerTransaction.bookingTime}} < :to
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
),
moves AS (
    SELECT tx.tx_key, tx.src, tx.descr, tx.reverses,
           a.{{LedgerAccount.accountCode}} AS code,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END AS net,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END AS debit
    FROM tx
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = tx.tx_key
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
),
per_tx AS (
    SELECT tx_key, src, descr, reverses,
           sum(debit) AS gross,
           coalesce(sum(net) FILTER (WHERE code = '1110'), 0) AS a1110,
           coalesce(sum(net) FILTER (WHERE code = '1120'), 0) AS a1120,
           coalesce(sum(net) FILTER (WHERE code = '2110'), 0) AS a2110,
           coalesce(sum(net) FILTER (WHERE code = '2120'), 0) AS a2120,
           coalesce(sum(net) FILTER (WHERE code = '2130'), 0) AS a2130,
           coalesce(sum(net) FILTER (WHERE code = '4110'), 0) AS a4110,
           coalesce(sum(net) FILTER (WHERE code = '5110'), 0) AS a5110
    FROM moves
    GROUP BY tx_key, src, descr, reverses
),
kinds AS (
    SELECT p.*,
           CASE
               WHEN p.src = 'QbTransfer' AND p.reverses IS NOT NULL THEN 'TRANSFER_FAILED'
               WHEN p.src = 'QbStripePayout' AND p.reverses IS NOT NULL THEN 'PAYOUT_FAILED'
               WHEN p.reverses IS NOT NULL THEN 'OTHER'
               WHEN p.src = 'QbTopUp' AND p.a2110 < 0 THEN 'TOPUP'
               WHEN p.src = 'QbTopUp' AND p.a2110 > 0 AND p.descr = 'Lost dispute' THEN 'DISPUTE'
               WHEN p.src = 'QbTopUp' AND p.a2110 > 0 THEN 'REFUND'
               WHEN p.src = 'QbTopUp' AND p.a5110 <> 0 THEN 'STRIPE_FEE'
               WHEN p.src = 'QbAttempt' THEN 'REWARD'
               WHEN p.src = 'QbTransfer' AND p.a2130 < 0 THEN 'TRANSFER_SENT'
               WHEN p.src = 'QbTransfer' AND p.a2130 > 0 THEN 'TRANSFER_SETTLED'
               WHEN p.src = 'QbTransfer' AND p.a2120 < 0 THEN 'TRANSFER_REVERSED'
               WHEN p.src = 'QbStripePayout' THEN 'PAYOUT'
               ELSE 'OTHER'
           END AS kind
    FROM per_tx p
)
SELECT
    c.seq                       AS seq,
    CAST(c.kind AS varchar(30)) AS category,
    count(k.tx_key)             AS transactionCount,
    coalesce(sum(k.gross), 0)   AS amount,
    coalesce(sum(k.a1110), 0)   AS stripeBalance,
    coalesce(sum(k.a1120), 0)   AS bank,
    coalesce(sum(k.a2110), 0)   AS sponsorWallets,
    coalesce(sum(k.a2120), 0)   AS takerWallets,
    coalesce(sum(k.a2130), 0)   AS inTransit,
    coalesce(sum(k.a4110), 0)   AS revenue,
    coalesce(sum(k.a5110), 0)   AS paymentFees
FROM (VALUES (1, 'TOPUP'), (2, 'REFUND'), (3, 'DISPUTE'), (4, 'STRIPE_FEE'), (5, 'REWARD'), (6, 'TRANSFER_SENT'),
             (7, 'TRANSFER_SETTLED'), (8, 'TRANSFER_FAILED'), (9, 'TRANSFER_REVERSED'), (10, 'PAYOUT'),
             (11, 'PAYOUT_FAILED'), (12, 'OTHER')) AS c (seq, kind)
LEFT JOIN kinds k ON k.kind = c.kind
GROUP BY c.seq, c.kind
