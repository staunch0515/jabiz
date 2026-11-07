/*---
id: qb.admin.verify-unbalanced-periods
description: >-
  The months (by booking time, UTC) whose debits differ from their credits; none when the ledger is sound. A restore
  check (operations.md section 2.4, DRILL_TEMPLATES): rows only when there is a problem
entities: [LedgerTransaction, LedgerEntry]
results:
  period: { kind: { type: text, maxLength: 7 } }
  debit:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  credit: { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [period]
  defaultSort: { field: period, asc: true }
  key: [period]
permissions: [qb.admin.finance.read]
---*/
-- LEDGER_POST refuses an unbalanced transaction, so a row here means the ledger was changed outside it, or a restore
-- that is not consistent (part of a transaction's lines). Every month, not only the last: the copy is checked whole.
SELECT m.period, m.debit, m.credit
FROM (
    SELECT CAST(to_char(t.{{LedgerTransaction.bookingTime}} AT TIME ZONE 'UTC', 'YYYY-MM') AS varchar(7)) AS period,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END) AS debit,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END) AS credit
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    GROUP BY 1) m
WHERE m.debit <> m.credit
