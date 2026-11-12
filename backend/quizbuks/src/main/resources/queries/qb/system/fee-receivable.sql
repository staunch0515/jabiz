/*---
id: qb.system.fee-receivable
description: "What a sponsor owes of the platform's fees (F-M5): account 1130 for them, the unpaid invoices and the postpaid fees not invoiced yet; read by a postpaid sponsor's submission under their wallet lock"
entities: [LedgerEntry, LedgerAccount]
params:
  sponsorId: { kind: { type: text, maxLength: 128 }, required: true, description: The sponsor }
results:
  owed: { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.fee.system]
---*/
-- Always one row: debit less credit of 1130 on the sponsor's lines (a charge debits it when made, its payment credits
-- it, a voided invoice's reversal undoes both).
SELECT coalesce(sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
           ELSE -e.{{LedgerEntry.amount}} END), 0) AS owed
FROM {{LedgerEntry}} e
JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
WHERE a.{{LedgerAccount.accountCode}} = '1130'
  AND e.{{LedgerEntry.dimension1}} = :sponsorId
