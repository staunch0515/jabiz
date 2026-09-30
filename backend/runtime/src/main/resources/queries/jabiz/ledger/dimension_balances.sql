/*---
id: jabiz.ledger.dimension_balances
description: >-
  Debit and credit totals and the balance of every account by the value of one analysis dimension (its position, 1
  to 4), over the transactions booked in a range and recorded up to a time (if given). Entries without a value of
  the dimension are counted with an empty value.
entities: [LedgerAccount, LedgerTransaction, LedgerEntry]
params:
  dimension: { kind: { type: numeric, precision: 1, scale: 0 }, required: true, description: "position of the dimension, 1 to 4" }
  asOf:      { like: LedgerTransaction.bookingTime, required: true, description: transactions booked at or before this time count }
  from:      { like: LedgerTransaction.bookingTime, description: "if given, only transactions booked at or after this time count" }
  knownAt:   { like: LedgerTransaction.bookingTime, description: "if given, the ledger (accounts included) as recorded at this time" }
results:
  accountCode:    { from: LedgerAccount.accountCode }
  accountName:    { from: LedgerAccount.accountName }
  dimensionValue: { from: LedgerEntry.dimension1 }
  debitTotal:     { from: LedgerEntry.amount }
  creditTotal:    { from: LedgerEntry.amount }
  balance:        { from: LedgerEntry.amount }
list:
  filters: [accountCode, dimensionValue]
  sorts:   [accountCode, dimensionValue, balance]
  defaultSort: { field: accountCode, asc: true }
  key: [accountCode, dimensionValue]
permissions: [ledger.read]
# The ledger is read as recorded at knownAt, accounts included; asOf filters by booking time only, since a
# back-dated transaction is in effect from when it was recorded (docs/design/19-reports.md section 2.2).
timeSlice: { knownAt: knownAt }
report:
  period: { from: from, to: asOf }
---*/
SELECT
    a.{{LedgerAccount.accountCode}} AS accountCode,
    a.{{LedgerAccount.accountName}} AS accountName,
    b.dimension_value AS dimensionValue,
    COALESCE(SUM(CASE WHEN b.entry_side = 'DEBIT' THEN b.entry_amount END), 0) AS debitTotal,
    COALESCE(SUM(CASE WHEN b.entry_side = 'CREDIT' THEN b.entry_amount END), 0) AS creditTotal,
    COALESCE(SUM(CASE WHEN b.entry_side = 'DEBIT' THEN b.entry_amount ELSE -b.entry_amount END), 0) AS balance
FROM (
    SELECT e.{{LedgerEntry.accountId}} AS entry_account,
           e.{{LedgerEntry.direction}} AS entry_side,
           e.{{LedgerEntry.amount}}    AS entry_amount,
           COALESCE(CASE CAST(:dimension AS integer)
               WHEN 1 THEN e.{{LedgerEntry.dimension1}}
               WHEN 2 THEN e.{{LedgerEntry.dimension2}}
               WHEN 3 THEN e.{{LedgerEntry.dimension3}}
               WHEN 4 THEN e.{{LedgerEntry.dimension4}}
           END, '') AS dimension_value
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t
      ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE t.{{LedgerTransaction.bookingTime}} <= :asOf
      AND (CAST(:from AS timestamptz) IS NULL OR t.{{LedgerTransaction.bookingTime}} >= :from)
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
) b
JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = b.entry_account
GROUP BY a.{{LedgerAccount.accountCode}}, a.{{LedgerAccount.accountName}}, b.dimension_value
