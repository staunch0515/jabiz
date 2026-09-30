/*---
id: jabiz.ledger.account_balances
description: >-
  Debit and credit totals and the balance (debit minus credit) of every account over the transactions booked up to a
  time (and from a time, if given), as recorded up to a time (if given). A summary account shows the totals of all
  its sub-accounts.
entities: [LedgerAccount, LedgerTransaction, LedgerEntry]
params:
  asOf:    { like: LedgerTransaction.bookingTime, required: true, description: transactions booked at or before this time count }
  from:    { like: LedgerTransaction.bookingTime, description: "if given, only transactions booked at or after this time count" }
  knownAt: { like: LedgerTransaction.bookingTime, description: "if given, only transactions recorded at or before this time count" }
results:
  accountCode: { from: LedgerAccount.accountCode }
  accountName: { from: LedgerAccount.accountName }
  accountType: { from: LedgerAccount.accountType }
  parentCode:  { from: LedgerAccount.accountCode }
  summary:     { from: LedgerAccount.summary }
  level:       { kind: { type: numeric, precision: 9, scale: 0 } }
  debitTotal:  { from: LedgerEntry.amount }
  creditTotal: { from: LedgerEntry.amount }
  balance:     { from: LedgerEntry.amount }
list:
  filters: [accountCode, accountType, parentCode, summary, level]
  sorts:   [accountCode, balance, level]
  defaultSort: { field: accountCode, asc: true }
  key: [accountCode]
permissions: [ledger.read]
---*/
WITH RECURSIVE tree (acct_key, ancestor_id) AS (
    SELECT a.{{LedgerAccount.accountId}}, a.{{LedgerAccount.accountId}}
    FROM {{LedgerAccount}} a
    UNION
    SELECT t.acct_key, p.{{LedgerAccount.parentId}}
    FROM tree t
    JOIN {{LedgerAccount}} p ON p.{{LedgerAccount.accountId}} = t.ancestor_id
    WHERE p.{{LedgerAccount.parentId}} IS NOT NULL
),
moves AS (
    SELECT e.{{LedgerEntry.accountId}} AS entry_account,
           e.{{LedgerEntry.direction}} AS entry_side,
           e.{{LedgerEntry.amount}}    AS entry_amount
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t
      ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE t.{{LedgerTransaction.bookingTime}} <= :asOf
      AND (CAST(:from AS timestamptz) IS NULL OR t.{{LedgerTransaction.bookingTime}} >= :from)
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
),
totals AS (
    SELECT tree.ancestor_id AS acct_key,
           SUM(CASE WHEN m.entry_side = 'DEBIT' THEN m.entry_amount END) AS debit_total,
           SUM(CASE WHEN m.entry_side = 'CREDIT' THEN m.entry_amount END) AS credit_total
    FROM moves m
    JOIN tree ON tree.acct_key = m.entry_account
    GROUP BY tree.ancestor_id
),
depth AS (
    SELECT acct_key, COUNT(*) - 1 AS depth_level FROM tree GROUP BY acct_key
)
SELECT
    a.{{LedgerAccount.accountCode}} AS accountCode,
    a.{{LedgerAccount.accountName}} AS accountName,
    a.{{LedgerAccount.accountType}} AS accountType,
    p.{{LedgerAccount.accountCode}} AS parentCode,
    COALESCE(a.{{LedgerAccount.summary}}, false) AS summary,
    d.depth_level AS level,
    COALESCE(s.debit_total, 0) AS debitTotal,
    COALESCE(s.credit_total, 0) AS creditTotal,
    COALESCE(s.debit_total, 0) - COALESCE(s.credit_total, 0) AS balance
FROM {{LedgerAccount}} a
LEFT JOIN {{LedgerAccount}} p ON p.{{LedgerAccount.accountId}} = a.{{LedgerAccount.parentId}}
JOIN depth d ON d.acct_key = a.{{LedgerAccount.accountId}}
LEFT JOIN totals s ON s.acct_key = a.{{LedgerAccount.accountId}}
