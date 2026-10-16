/*---
id: qb.system.journal-summary
description: >-
  The summary voucher of a journal export (Q9d, D-Q9d-2, D-Q9d-4): what it takes from the ledger summed by account and
  side, and by sponsor on 2110 (the takers' accounts summed), with how many transactions each line sums - a few hundred
  rows however many postings the month has
entities: [LedgerAccount, LedgerTransaction, LedgerEntry]
params:
  to:              { like: LedgerTransaction.bookingTime, required: true, description: "The period's end: transactions booked before it" }
  knownAt:         { like: LedgerTransaction.bookingTime, required: true, description: "The export's cut-off: transactions recorded by then" }
  previousTo:      { like: LedgerTransaction.bookingTime, description: "The previous export's period end (none for the first)" }
  previousKnownAt: { like: LedgerTransaction.bookingTime, description: "The previous export's cut-off (none for the first)" }
results:
  accountCode:  { from: LedgerAccount.accountCode }
  direction:    { from: LedgerEntry.direction }
  party:        { kind: { type: text, maxLength: 128 } }
  amount:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  transactions: { kind: { type: numeric, precision: 12, scale: 0 } }
permissions: [qb.journal.export]
---*/
-- The same transactions as qb.system.journal-detail takes (see there).
SELECT
    a.{{LedgerAccount.accountCode}} AS accountCode,
    e.{{LedgerEntry.direction}}     AS direction,
    CAST(CASE WHEN a.{{LedgerAccount.accountCode}} = '2110' THEN e.{{LedgerEntry.dimension1}} END AS varchar(128))
                                    AS party,
    sum(e.{{LedgerEntry.amount}})   AS amount,
    count(DISTINCT t.{{LedgerTransaction.transactionId}}) AS transactions
FROM {{LedgerTransaction}} t
JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
WHERE t.{{LedgerTransaction.bookingTime}} < :to
  AND t.{{LedgerTransaction.createdTime}} <= :knownAt
  AND (CAST(:previousTo AS timestamptz) IS NULL
       OR t.{{LedgerTransaction.bookingTime}} >= :previousTo
       OR t.{{LedgerTransaction.createdTime}} > :previousKnownAt)
GROUP BY a.{{LedgerAccount.accountCode}}, e.{{LedgerEntry.direction}},
         CASE WHEN a.{{LedgerAccount.accountCode}} = '2110' THEN e.{{LedgerEntry.dimension1}} END
