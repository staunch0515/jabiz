/*---
id: qb.system.journal-lines
description: >-
  What a journal export takes from the ledger (Q9d, D-Q9d-3): each transaction's postings by account and side (and the
  sponsor on 2110), of every transaction booked before the period's end and recorded by the export's cut-off that no
  earlier export took
entities: [LedgerAccount, LedgerTransaction, LedgerEntry]
params:
  to:              { like: LedgerTransaction.bookingTime, required: true, description: "The period's end: transactions booked before it" }
  knownAt:         { like: LedgerTransaction.bookingTime, required: true, description: "The export's cut-off: transactions recorded by then" }
  previousTo:      { like: LedgerTransaction.bookingTime, description: "The previous export's period end (none for the first)" }
  previousKnownAt: { like: LedgerTransaction.bookingTime, description: "The previous export's cut-off (none for the first)" }
results:
  transactionId: { from: LedgerTransaction.transactionId }
  bookingTime:   { from: LedgerTransaction.bookingTime }
  recordedAt:    { kind: { type: temporal, role: EVENT_TIME } }
  accountCode:   { from: LedgerAccount.accountCode }
  direction:     { from: LedgerEntry.direction }
  party:         { kind: { type: text, maxLength: 128 } }
  amount:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  sourceEntity:  { from: LedgerTransaction.sourceEntity }
  sourceId:      { from: LedgerTransaction.sourceId }
  reference:     { from: LedgerTransaction.reference }
permissions: [qb.journal.export]
---*/
-- The exports so far took every transaction booked before the previous period's end and recorded by the previous
-- cut-off (each export's period end and cut-off are later than the one's before: their takings nest). This one takes
-- what is booked before its own end and recorded by its own cut-off, less that: a transaction booked in an exported
-- month but recorded after its export (a late refund, a correction) comes in the next export, once, and the exported
-- voucher stays as it is. A transaction's postings share its recorded time, so it is taken whole or not at all.
-- The party (dimension 1) only for 2110: the sponsor's line; the takers' accounts are summed (D-2).
SELECT
    t.{{LedgerTransaction.transactionId}} AS transactionId,
    t.{{LedgerTransaction.bookingTime}}   AS bookingTime,
    t.{{LedgerTransaction.createdTime}}   AS recordedAt,
    a.{{LedgerAccount.accountCode}}       AS accountCode,
    e.{{LedgerEntry.direction}}           AS direction,
    CAST(CASE WHEN a.{{LedgerAccount.accountCode}} = '2110' THEN e.{{LedgerEntry.dimension1}} END AS varchar(128))
                                          AS party,
    sum(e.{{LedgerEntry.amount}})         AS amount,
    t.{{LedgerTransaction.sourceEntity}}  AS sourceEntity,
    t.{{LedgerTransaction.sourceId}}      AS sourceId,
    t.{{LedgerTransaction.reference}}     AS reference
FROM {{LedgerTransaction}} t
JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
WHERE t.{{LedgerTransaction.bookingTime}} < :to
  AND t.{{LedgerTransaction.createdTime}} <= :knownAt
  AND (CAST(:previousTo AS timestamptz) IS NULL
       OR t.{{LedgerTransaction.bookingTime}} >= :previousTo
       OR t.{{LedgerTransaction.createdTime}} > :previousKnownAt)
GROUP BY t.{{LedgerTransaction.transactionId}}, t.{{LedgerTransaction.bookingTime}},
         t.{{LedgerTransaction.createdTime}}, a.{{LedgerAccount.accountCode}}, e.{{LedgerEntry.direction}},
         CASE WHEN a.{{LedgerAccount.accountCode}} = '2110' THEN e.{{LedgerEntry.dimension1}} END,
         t.{{LedgerTransaction.sourceEntity}}, t.{{LedgerTransaction.sourceId}}, t.{{LedgerTransaction.reference}}
