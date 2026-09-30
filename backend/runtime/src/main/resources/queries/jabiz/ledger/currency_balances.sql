/*---
id: jabiz.ledger.currency_balances
description: >-
  The balance of every account by transaction currency: in that currency and in the ledger currency, over the
  transactions booked up to a time and recorded up to a time (if given). Entries in the ledger currency are shown with
  an empty currency. The open foreign amounts a revaluation remeasures at the closing rate.
entities: [LedgerAccount, LedgerTransaction, LedgerEntry]
params:
  asOf:    { like: LedgerTransaction.bookingTime, required: true, description: transactions booked at or before this time count }
  knownAt: { like: LedgerTransaction.bookingTime, description: "if given, the ledger (accounts included) as recorded at this time" }
results:
  accountCode:        { from: LedgerAccount.accountCode }
  accountName:        { from: LedgerAccount.accountName }
  currency:           { from: LedgerEntry.currency }
  transactionBalance: { from: LedgerEntry.transactionAmount }
  balance:            { from: LedgerEntry.amount }
list:
  filters: [accountCode, currency]
  sorts:   [accountCode, currency]
  defaultSort: { field: accountCode, asc: true }
  key: [accountCode, currency]
permissions: [ledger.read]
# The ledger is read as recorded at knownAt, accounts included; asOf filters by booking time only, since a
# back-dated transaction is in effect from when it was recorded (docs/design/19-reports.md section 2.2).
timeSlice: { knownAt: knownAt }
report:
  period: { to: asOf }
---*/
SELECT
    a.{{LedgerAccount.accountCode}} AS accountCode,
    a.{{LedgerAccount.accountName}} AS accountName,
    b.entry_currency AS currency,
    SUM(CASE WHEN b.entry_side = 'DEBIT' THEN b.entry_foreign ELSE -b.entry_foreign END) AS transactionBalance,
    SUM(CASE WHEN b.entry_side = 'DEBIT' THEN b.entry_amount ELSE -b.entry_amount END) AS balance
FROM (
    SELECT e.{{LedgerEntry.accountId}} AS entry_account,
           e.{{LedgerEntry.direction}} AS entry_side,
           e.{{LedgerEntry.amount}}    AS entry_amount,
           COALESCE(e.{{LedgerEntry.currency}}, '') AS entry_currency,
           COALESCE(e.{{LedgerEntry.transactionAmount}}, e.{{LedgerEntry.amount}}) AS entry_foreign
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t
      ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE t.{{LedgerTransaction.bookingTime}} <= :asOf
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
) b
JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = b.entry_account
GROUP BY a.{{LedgerAccount.accountCode}}, a.{{LedgerAccount.accountName}}, b.entry_currency
