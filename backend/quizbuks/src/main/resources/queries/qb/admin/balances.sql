/*---
id: qb.admin.balances
description: >-
  The month's balance sheet (F-08): each account's opening balance, debits, credits and closing balance, then the
  checks that the wallet accounts add up to their parties and the ledger balances; from the ledger alone, by booking
  time, as recorded up to a time (if given)
entities: [LedgerAccount, LedgerTransaction, LedgerEntry]
params:
  from:    { like: LedgerTransaction.bookingTime, required: true, description: "The month's first instant: the opening balance is everything booked before it" }
  to:      { like: LedgerTransaction.bookingTime, required: true, description: "The next month's first instant: the closing balance is everything booked before it" }
  knownAt: { like: LedgerTransaction.bookingTime, description: "if given, the ledger (accounts included) as recorded at this time" }
results:
  seq:         { kind: { type: numeric, precision: 3, scale: 0 } }
  rowKind:     { kind: { type: text, maxLength: 10 } }
  code:        { kind: { type: text, maxLength: 50 } }
  name:        { kind: { type: text, maxLength: 200 } }
  accountType: { from: LedgerAccount.accountType }
  opening:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  debit:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  credit:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  closing:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  partyTotal:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  matches:     { kind: { type: bool } }
list:
  filters: [rowKind, code]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [seq]
permissions: [qb.admin.finance.read]
# As the platform's ledger reports (docs/design/19-reports.md section 2.2): knownAt reads the ledger as recorded then,
# the month is a condition on the booking time only.
timeSlice: { knownAt: knownAt }
report:
  period: { from: from, to: to }
  landscape: true
---*/
-- ACCOUNT rows, by code: every postable account, its balance being debit less credit as in
-- jabiz.ledger.account_balances (whose balance at the month's last instant is the closing here). CHECK rows, which
-- read the ledger only (its wallet caches are the daily reconciliation's, qb.admin.recon-runs):
--   SPONSOR_WALLETS: 2110 against the sum of its sponsors' balances (the party, dimension 1, of every line);
--   TAKER_WALLETS:   2120 + 2130 (the takers' balances and what is on its way to them) against the sum of their parties;
--   BALANCED:        the month's debits against its credits, and every closing balance adding up to 0.
-- partyTotal is what the lines with a party add up to; a line without one shows as a difference (matches false).
WITH moves AS (
    SELECT e.{{LedgerEntry.accountId}}   AS acct,
           e.{{LedgerEntry.dimension1}}  AS party,
           t.{{LedgerTransaction.bookingTime}} < :from AS earlier,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END AS debit,
           CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END AS credit
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE t.{{LedgerTransaction.bookingTime}} < :to
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
),
accounts AS (
    SELECT a.{{LedgerAccount.accountCode}} AS code,
           a.{{LedgerAccount.accountName}} AS name,
           a.{{LedgerAccount.accountType}} AS acct_kind,
           coalesce(sum(m.debit - m.credit) FILTER (WHERE m.earlier), 0) AS opening,
           coalesce(sum(m.debit) FILTER (WHERE NOT m.earlier), 0)        AS debit_total,
           coalesce(sum(m.credit) FILTER (WHERE NOT m.earlier), 0)       AS credit_total,
           coalesce(sum(m.debit - m.credit), 0)                          AS closing,
           coalesce(sum(m.debit - m.credit) FILTER (WHERE m.party IS NOT NULL), 0) AS party_total
    FROM {{LedgerAccount}} a
    LEFT JOIN moves m ON m.acct = a.{{LedgerAccount.accountId}}
    WHERE NOT coalesce(a.{{LedgerAccount.summary}}, false)
    GROUP BY a.{{LedgerAccount.accountCode}}, a.{{LedgerAccount.accountName}}, a.{{LedgerAccount.accountType}}
)
SELECT CAST(row_number() OVER (ORDER BY code) AS integer) AS seq,
       CAST('ACCOUNT' AS varchar(10)) AS rowKind,
       CAST(code AS varchar(50))      AS code,
       CAST(name AS varchar(200))     AS name,
       acct_kind                      AS accountType,
       opening, debit_total AS debit, credit_total AS credit, closing,
       CAST(NULL AS numeric)          AS partyTotal,
       CAST(NULL AS boolean)          AS matches
FROM accounts
UNION ALL
SELECT 901, 'CHECK', 'SPONSOR_WALLETS', 'Sponsor wallets (2110)', NULL,
       coalesce(sum(opening), 0), coalesce(sum(debit_total), 0), coalesce(sum(credit_total), 0),
       coalesce(sum(closing), 0), coalesce(sum(party_total), 0),
       coalesce(sum(closing), 0) = coalesce(sum(party_total), 0)
FROM accounts WHERE code = '2110'
UNION ALL
SELECT 902, 'CHECK', 'TAKER_WALLETS', 'User wallets (2120 + 2130)', NULL,
       coalesce(sum(opening), 0), coalesce(sum(debit_total), 0), coalesce(sum(credit_total), 0),
       coalesce(sum(closing), 0), coalesce(sum(party_total), 0),
       coalesce(sum(closing), 0) = coalesce(sum(party_total), 0)
FROM accounts WHERE code IN ('2120', '2130')
UNION ALL
SELECT 903, 'CHECK', 'BALANCED', 'Debits equal credits', NULL,
       coalesce(sum(opening), 0), coalesce(sum(debit_total), 0), coalesce(sum(credit_total), 0),
       coalesce(sum(closing), 0), NULL,
       coalesce(sum(debit_total), 0) = coalesce(sum(credit_total), 0) AND coalesce(sum(closing), 0) = 0
FROM accounts
