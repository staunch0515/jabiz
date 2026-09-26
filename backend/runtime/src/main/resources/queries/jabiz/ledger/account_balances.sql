/*---
id: jabiz.ledger.account_balances
description: Debit and credit totals and the balance (debit minus credit) of every account, over the transactions booked up to a time
entities: [LedgerAccount, LedgerTransaction, LedgerEntry]
params:
  asOf: { like: LedgerTransaction.bookingTime, required: true, description: transactions booked at or before this time count }
results:
  accountCode: { from: LedgerAccount.accountCode }
  accountName: { from: LedgerAccount.accountName }
  accountType: { from: LedgerAccount.accountType }
  debitTotal:  { from: LedgerEntry.amount }
  creditTotal: { from: LedgerEntry.amount }
  balance:     { from: LedgerEntry.amount }
list:
  filters: [accountCode, accountType]
  sorts:   [accountCode, balance]
  defaultSort: { field: accountCode, asc: true }
  key: [accountCode]
permissions: [ledger.read]
---*/
SELECT
    a.{{LedgerAccount.accountCode}} AS accountCode,
    a.{{LedgerAccount.accountName}} AS accountName,
    a.{{LedgerAccount.accountType}} AS accountType,
    COALESCE(SUM(CASE WHEN b.entry_side = 'DEBIT' THEN b.entry_amount END), 0) AS debitTotal,
    COALESCE(SUM(CASE WHEN b.entry_side = 'CREDIT' THEN b.entry_amount END), 0) AS creditTotal,
    COALESCE(SUM(CASE WHEN b.entry_side = 'DEBIT' THEN b.entry_amount ELSE -b.entry_amount END), 0) AS balance
FROM {{LedgerAccount}} a
LEFT JOIN (
    SELECT e.{{LedgerEntry.accountId}} AS entry_account,
           e.{{LedgerEntry.direction}} AS entry_side,
           e.{{LedgerEntry.amount}}    AS entry_amount
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t
      ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE t.{{LedgerTransaction.bookingTime}} <= :asOf
) b ON b.entry_account = a.{{LedgerAccount.accountId}}
GROUP BY a.{{LedgerAccount.accountCode}}, a.{{LedgerAccount.accountName}}, a.{{LedgerAccount.accountType}}
