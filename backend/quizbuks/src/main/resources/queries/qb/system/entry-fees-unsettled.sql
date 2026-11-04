/*---
id: qb.system.entry-fees-unsettled
description: One sponsor's entry fees not yet settled into their cash account (account 2170 for them, Q88b), read by QB_ENTRY_FEE_SETTLE under the sponsor's wallet lock
entities: [LedgerEntry, LedgerAccount]
params:
  sponsorId: { kind: { type: text, maxLength: 128 }, required: true, description: The sponsor }
results:
  sponsorId: { kind: { type: text, maxLength: 128 } }
  unsettled: { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  key: [sponsorId]
permissions: [qb.wallet.system]
---*/
-- One statement: what is committed and what this transaction posted. Credit less debit (a liability); 0 with no lines.
SELECT CAST(:sponsorId AS varchar(128)) AS sponsorId,
       coalesce(sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
                         ELSE -e.{{LedgerEntry.amount}} END), 0) AS unsettled
FROM {{LedgerEntry}} e
JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
WHERE a.{{LedgerAccount.accountCode}} = '2170'
  AND e.{{LedgerEntry.dimension1}} = CAST(:sponsorId AS text)
