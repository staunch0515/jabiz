/*---
id: qb.system.journal-first
description: When the earliest posting of the ledger was booked - the first journal export's month (Q9d)
entities: [LedgerTransaction]
results:
  firstBooking: { kind: { type: temporal, role: EVENT_TIME } }
permissions: [qb.journal.export]
---*/
-- One row, empty (null) when nothing was ever posted.
SELECT min(t.{{LedgerTransaction.bookingTime}}) AS firstBooking
FROM {{LedgerTransaction}} t
