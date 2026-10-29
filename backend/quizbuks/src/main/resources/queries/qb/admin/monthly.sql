/*---
id: qb.admin.monthly
description: >-
  The month's transactions by kind (F-08): how many and how much, and what they moved on each account (debit less
  credit); from the ledger alone, by booking time, as recorded up to a time (if given). Its months are UTC months
  (from, to); the journal export to the accounting system (qb.admin.journal-exports, Q9d) is by Japanese months, nine
  hours earlier, and takes late postings in its next export: the two differ by the postings near a month's edge
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
  promotion:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  operatorPayable:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  operatorShares:   { kind: { type: monetary, currency: JPY, scale: 0 } }
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
-- Every kind is a row, in a fixed order, also when the month has none of it. A transaction is of a kind only when
-- everything about it matches: where it was posted from (its source document), whether it reverses another, the exact
-- set of accounts it moved and the direction of each (QbLedger and the wallet processes post each kind from one place;
-- AdminFinanceIT checks every kind). Anything else is OTHER: the development credit, a posting of no source, and any
-- new kind of posting until it is added here. A refund and a lost dispute move the same accounts; they differ in their
-- reference, the Stripe object they were posted for (a charge ch_ / py_, a dispute dp_ / du_: StripeIds); so do a bank
-- top-up and money received outside the system credited by hand (Q18b), whose reference is mn_ and its code. An
-- operator's settled share (with the prospecting cost it recovers, Q48d) and its payment (Q48b) are posted from the
-- settlement. A reversal
-- keeps its original's source and reference: a transfer's is the failed sending, a payout's the failed payout.
-- amount is what the transactions moved (the sum of their debits); the account columns are each account's debit less
-- credit, as in jabiz.ledger.account_balances (an asset grows positive, a liability or revenue negative), so that a
-- row adds up to 0 and a column to the account's movement in the month (qb.admin.balances: debit less credit).
-- The month is by booking time: a reward counts when it was posted, not when its attempt was submitted.
WITH tx AS (
    SELECT t.{{LedgerTransaction.transactionId}}         AS tx_key,
           t.{{LedgerTransaction.sourceEntity}}          AS src,
           left(coalesce(t.{{LedgerTransaction.reference}}, ''), 3) AS ref_kind,
           t.{{LedgerTransaction.reversesTransactionId}} IS NOT NULL AS reversal
    FROM {{LedgerTransaction}} t
    WHERE t.{{LedgerTransaction.bookingTime}} >= :from
      AND t.{{LedgerTransaction.bookingTime}} < :to
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
),
moves AS (
    SELECT tx.tx_key, tx.src, tx.ref_kind, tx.reversal,
           a.{{LedgerAccount.accountCode}} AS code,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END AS net,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END AS debit
    FROM tx
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = tx.tx_key
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
),
per_tx AS (
    SELECT tx_key, src, ref_kind, reversal,
           sum(debit) AS gross,
           string_agg(DISTINCT code, ',' ORDER BY code) AS accts,
           coalesce(sum(net) FILTER (WHERE code = '1110'), 0) AS a1110,
           coalesce(sum(net) FILTER (WHERE code = '1120'), 0) AS a1120,
           coalesce(sum(net) FILTER (WHERE code = '2110'), 0) AS a2110,
           coalesce(sum(net) FILTER (WHERE code = '2120'), 0) AS a2120,
           coalesce(sum(net) FILTER (WHERE code = '2130'), 0) AS a2130,
           coalesce(sum(net) FILTER (WHERE code = '4110'), 0) AS a4110,
           coalesce(sum(net) FILTER (WHERE code = '5110'), 0) AS a5110,
           coalesce(sum(net) FILTER (WHERE code = '5120'), 0) AS a5120,
           coalesce(sum(net) FILTER (WHERE code = '2140'), 0) AS a2140,
           coalesce(sum(net) FILTER (WHERE code = '5130'), 0) AS a5130
    FROM moves
    GROUP BY tx_key, src, ref_kind, reversal
),
kinds AS (
    SELECT p.*,
           CASE
               WHEN p.src = 'QbTopUp' AND NOT p.reversal AND p.accts = '1110,2110'
                    AND p.a1110 > 0 AND p.a2110 < 0 THEN 'TOPUP'
               WHEN p.src = 'QbTopUp' AND NOT p.reversal AND p.accts = '1110,2110'
                    AND p.a1110 < 0 AND p.a2110 > 0 AND p.ref_kind IN ('ch_', 'py_') THEN 'REFUND'
               WHEN p.src = 'QbTopUp' AND NOT p.reversal AND p.accts = '1110,2110'
                    AND p.a1110 < 0 AND p.a2110 > 0 AND p.ref_kind IN ('dp_', 'du_') THEN 'DISPUTE'
               WHEN p.src = 'QbTopUp' AND NOT p.reversal AND p.accts = '1120,2110'
                    AND p.a1120 > 0 AND p.a2110 < 0 AND p.ref_kind = 'mn_' THEN 'MANUAL_TOPUP'
               WHEN p.src = 'QbTopUp' AND NOT p.reversal AND p.accts = '1120,2110'
                    AND p.a1120 > 0 AND p.a2110 < 0 AND p.ref_kind <> 'mn_' THEN 'BANK_TOPUP'
               WHEN p.src = 'QbTopUp' AND NOT p.reversal AND p.accts = '2110,5120'
                    AND p.a5120 > 0 AND p.a2110 < 0 AND p.ref_kind = 'mn_' THEN 'GRANT'
               WHEN p.src = 'QbTopUp' AND NOT p.reversal AND p.accts = '1110,5110'
                    AND p.a1110 < 0 AND p.a5110 > 0 THEN 'STRIPE_FEE'
               WHEN p.src = 'QbAttempt' AND NOT p.reversal AND p.accts IN ('2110,2120', '2110,2120,4110', '2110,4110')
                    AND p.a2110 > 0 AND p.a2120 <= 0 AND p.a4110 <= 0 THEN 'REWARD'
               WHEN p.src = 'QbTransfer' AND NOT p.reversal AND p.accts IN ('2120,2130', '2120,2130,4110')
                    AND p.a2120 > 0 AND p.a2130 < 0 AND p.a4110 <= 0 THEN 'TRANSFER_SENT'
               WHEN p.src = 'QbTransfer' AND NOT p.reversal AND p.accts = '1110,2130'
                    AND p.a1110 < 0 AND p.a2130 > 0 THEN 'TRANSFER_SETTLED'
               WHEN p.src = 'QbTransfer' AND NOT p.reversal AND p.accts = '1120,2130'
                    AND p.a1120 < 0 AND p.a2130 > 0 THEN 'WITHDRAWAL_PAID'
               WHEN p.src = 'QbTransfer' AND p.reversal AND p.accts IN ('2120,2130', '2120,2130,4110')
                    AND p.a2120 < 0 AND p.a2130 > 0 AND p.a4110 >= 0 THEN 'TRANSFER_FAILED'
               WHEN p.src = 'QbTransfer' AND NOT p.reversal AND p.accts = '1110,2120'
                    AND p.a1110 > 0 AND p.a2120 < 0 THEN 'TRANSFER_REVERSED'
               WHEN p.src = 'QbStripePayout' AND NOT p.reversal AND p.accts = '1110,1120'
                    AND p.a1120 > 0 AND p.a1110 < 0 THEN 'PAYOUT'
               WHEN p.src = 'QbStripePayout' AND p.reversal AND p.accts = '1110,1120'
                    AND p.a1120 < 0 AND p.a1110 > 0 THEN 'PAYOUT_FAILED'
               WHEN p.src = 'QbOperatorSettlement' AND NOT p.reversal
                    AND p.accts IN ('2140,5130', '2140,4110,5130', '4110,5130')
                    AND p.a5130 > 0 AND p.a2140 <= 0 AND p.a4110 <= 0 THEN 'OPERATOR_SHARE'
               WHEN p.src = 'QbOperatorSettlement' AND NOT p.reversal AND p.accts = '1120,2140'
                    AND p.a2140 > 0 AND p.a1120 < 0 THEN 'OPERATOR_PAID'
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
    coalesce(sum(k.a5110), 0)   AS paymentFees,
    coalesce(sum(k.a5120), 0)   AS promotion,
    coalesce(sum(k.a2140), 0)   AS operatorPayable,
    coalesce(sum(k.a5130), 0)   AS operatorShares
FROM (VALUES (1, 'TOPUP'), (2, 'BANK_TOPUP'), (3, 'MANUAL_TOPUP'), (4, 'GRANT'), (5, 'REFUND'), (6, 'DISPUTE'),
             (7, 'STRIPE_FEE'), (8, 'REWARD'), (9, 'TRANSFER_SENT'), (10, 'TRANSFER_SETTLED'), (11, 'WITHDRAWAL_PAID'),
             (12, 'TRANSFER_FAILED'), (13, 'TRANSFER_REVERSED'), (14, 'PAYOUT'), (15, 'PAYOUT_FAILED'),
             (16, 'OPERATOR_SHARE'), (17, 'OPERATOR_PAID'), (18, 'OTHER'))
         AS c (seq, kind)
LEFT JOIN kinds k ON k.kind = c.kind
GROUP BY c.seq, c.kind
