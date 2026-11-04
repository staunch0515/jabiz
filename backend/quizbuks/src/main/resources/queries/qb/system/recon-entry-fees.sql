/*---
id: qb.system.recon-entry-fees
description: Sponsors for whom account 2170 (entry fees unsettled, Q88b) is below zero, which no start or settlement should leave (the daily reconciliation; differences only)
entities: [LedgerEntry, LedgerAccount]
results:
  checkCode: { kind: { type: text, maxLength: 30 } }
  sponsorId: { kind: { type: text, maxLength: 128 } }
  expected:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  actual:    { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.wallet.system]
---*/
-- One statement, one snapshot. ENTRY_FEES_UNSETTLED: 2170 for a sponsor (credit less debit) is what their takers paid
-- and the daily job has not yet moved to 2160: never negative (a settlement moves what it read under the lock).
SELECT CAST('ENTRY_FEES_UNSETTLED' AS varchar(30)) AS checkCode,
       CAST(e.{{LedgerEntry.dimension1}} AS varchar(128)) AS sponsorId,
       CAST(0 AS numeric) AS expected,
       sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END) AS actual
FROM {{LedgerEntry}} e
JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
WHERE a.{{LedgerAccount.accountCode}} = '2170'
GROUP BY e.{{LedgerEntry.dimension1}}
HAVING sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END) < 0
