/*---
id: finance.gl.period_movements
description: >-
  A period's debits and credits by account, department and location over the entries posted in it (FinPosting's
  period), as recorded up to a time: what a period balance snapshot keeps (ROADMAP F9 decision D1), one bucket of the
  accounts at a time (by a hash of the account code) so that each read stays within a process's page.
entities: [LedgerAccount, LedgerTransaction, LedgerEntry, FinPosting]
params:
  periodKey: { like: FinPosting.periodKey, required: true }
  knownAt:   { like: LedgerTransaction.bookingTime, required: true, description: "entries recorded at or before this time count" }
  bucket:    { kind: { type: numeric, precision: 4, scale: 0 }, description: "only the accounts of this bucket, 0 to buckets - 1; all when not given" }
  buckets:   { kind: { type: numeric, precision: 4, scale: 0 }, description: "how many buckets the accounts are spread over" }
results:
  accountCode: { from: LedgerAccount.accountCode }
  department:  { from: LedgerEntry.dimension1 }
  location:    { from: LedgerEntry.dimension2 }
  debit:       { from: LedgerEntry.amount }
  credit:      { from: LedgerEntry.amount }
list:
  sorts:   [accountCode]
  defaultSort: { field: accountCode, asc: true }
  key: [accountCode, department, location]
permissions: [ledger.read]
---*/
SELECT a.{{LedgerAccount.accountCode}} AS accountCode,
       e.{{LedgerEntry.dimension1}} AS department,
       e.{{LedgerEntry.dimension2}} AS location,
       SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END) AS debit,
       SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END) AS credit
FROM {{LedgerEntry}} e
JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
WHERE fp.{{FinPosting.periodKey}} = :periodKey
  AND t.{{LedgerTransaction.createdTime}} <= :knownAt
  AND (CAST(:bucket AS integer) IS NULL
       OR MOD(ABS(CAST(HASHTEXT(a.{{LedgerAccount.accountCode}}) AS bigint)), CAST(:buckets AS integer)) = CAST(:bucket AS integer))
GROUP BY a.{{LedgerAccount.accountCode}}, e.{{LedgerEntry.dimension1}}, e.{{LedgerEntry.dimension2}}
