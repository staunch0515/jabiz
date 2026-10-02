/*---
id: finance.bank.cash_position
description: >-
  The cash position on a day (FIN-BK-010): every bank account's balance per the books at the end of the day and its
  last statement's closing balance by then; and the receipts and payments expected within the next days (30 if not
  given), overdue ones included: the open posted invoices and the open posted bills cleared for payment (approved,
  or needing no approval), by due date, in US dollars (a foreign document's at its own rate, F7). The open amounts are
  the documents' as recorded; run with knownAt for an
  earlier day's.
entities: [FinBankAccount, FinBankStatement, LedgerAccount, LedgerEntry, FinPosting, FinInvoice, FinCustomer, FinBill,
  FinVendor]
params:
  asOf:    { like: FinPosting.postingDate, required: true, description: "the day of the position, at its end" }
  horizon: { kind: { type: numeric, precision: 3, scale: 0 }, description: "days ahead of the expected receipts and payments; 30 if not given" }
results:
  section:          { kind: { type: text, maxLength: 20 } }
  bankCode:         { from: FinBankAccount.bankCode }
  glAccount:        { from: FinBankAccount.glAccount }
  bookBalance:      { from: LedgerEntry.amount }
  statementBalance: { from: FinBankStatement.closingBalance }
  statementDate:    { from: FinBankStatement.toDate }
  documentNo:       { from: FinBill.billNo }
  reference:        { from: FinBill.vendorInvoiceNo }
  party:            { from: FinVendor.legalName }
  dueDate:          { from: FinBill.dueDate }
  amount:           { from: FinBill.openAmount }
list:
  filters: [section, bankCode, party, reference]
  sorts:   [section, bankCode, dueDate, amount]
  defaultSort: { field: section, asc: true }
  key: [section, bankCode, documentNo]
permissions: [fin.bank.activity.read]
report:
  period: { to: asOf }
---*/
WITH reach AS (
    SELECT CAST(:asOf AS date) + CAST(COALESCE(:horizon, 30) AS integer) AS last_day
),
books AS (
    SELECT b.{{FinBankAccount.bankCode}} AS bank_key,
           COALESCE(SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                             ELSE -e.{{LedgerEntry.amount}} END), 0) AS book_total
    FROM {{FinBankAccount}} b
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountCode}} = b.{{FinBankAccount.glAccount}}
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.accountId}} = a.{{LedgerAccount.accountId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE fp.{{FinPosting.postingDate}} <= :asOf
    GROUP BY b.{{FinBankAccount.bankCode}}
),
last_statement AS (
    SELECT DISTINCT ON (s.{{FinBankStatement.bankCode}})
           s.{{FinBankStatement.bankCode}} AS bank_key, s.{{FinBankStatement.closingBalance}} AS closing,
           s.{{FinBankStatement.toDate}} AS closed_on
    FROM {{FinBankStatement}} s
    WHERE s.{{FinBankStatement.toDate}} <= :asOf
    ORDER BY s.{{FinBankStatement.bankCode}}, s.{{FinBankStatement.toDate}} DESC
)
SELECT 'ACCOUNT' AS section, b.{{FinBankAccount.bankCode}} AS bankCode, b.{{FinBankAccount.glAccount}} AS glAccount,
       COALESCE(bk.book_total, 0) AS bookBalance, ls.closing AS statementBalance, ls.closed_on AS statementDate,
       CAST(NULL AS varchar) AS documentNo, CAST(NULL AS varchar) AS reference, CAST(NULL AS varchar) AS party, CAST(NULL AS date) AS dueDate,
       CAST(NULL AS numeric) AS amount
FROM {{FinBankAccount}} b
LEFT JOIN books bk ON bk.bank_key = b.{{FinBankAccount.bankCode}}
LEFT JOIN last_statement ls ON ls.bank_key = b.{{FinBankAccount.bankCode}}
UNION ALL
SELECT 'EXPECTED_RECEIPT', NULL, NULL, NULL, NULL, NULL, i.{{FinInvoice.invoiceNo}}, NULL, c.{{FinCustomer.legalName}},
       i.{{FinInvoice.dueDate}}, COALESCE(i.{{FinInvoice.openAmountUsd}}, i.{{FinInvoice.openAmount}})
FROM {{FinInvoice}} i
CROSS JOIN reach
LEFT JOIN {{FinCustomer}} c ON c.{{FinCustomer.customerCode}} = i.{{FinInvoice.customerCode}}
WHERE i.{{FinInvoice.status}} = 'POSTED' AND i.{{FinInvoice.kind}} = 'INVOICE'
  AND i.{{FinInvoice.openAmount}} > 0 AND COALESCE(i.{{FinInvoice.postingDate}}, i.{{FinInvoice.invoiceDate}}) <= :asOf
  AND COALESCE(i.{{FinInvoice.dueDate}}, i.{{FinInvoice.invoiceDate}}) <= reach.last_day
UNION ALL
SELECT 'EXPECTED_PAYMENT', NULL, NULL, NULL, NULL, NULL, bl.{{FinBill.billNo}}, bl.{{FinBill.vendorInvoiceNo}}, v.{{FinVendor.legalName}},
       bl.{{FinBill.dueDate}}, COALESCE(bl.{{FinBill.openAmountUsd}}, bl.{{FinBill.openAmount}})
FROM {{FinBill}} bl
CROSS JOIN reach
LEFT JOIN {{FinVendor}} v ON v.{{FinVendor.vendorCode}} = bl.{{FinBill.vendorCode}}
WHERE bl.{{FinBill.status}} = 'POSTED' AND bl.{{FinBill.kind}} = 'BILL'
  AND bl.{{FinBill.approval}} IN ('NOT_REQUIRED', 'APPROVED')
  AND bl.{{FinBill.openAmount}} > 0 AND COALESCE(bl.{{FinBill.postingDate}}, bl.{{FinBill.invoiceDate}}) <= :asOf
  AND COALESCE(bl.{{FinBill.dueDate}}, bl.{{FinBill.invoiceDate}}) <= reach.last_day
