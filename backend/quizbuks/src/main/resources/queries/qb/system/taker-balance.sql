/*---
id: qb.system.taker-balance
description: A taker's balances in the ledger - account 2120 (available) and 2130 (on its way out) for them
entities: [LedgerEntry, LedgerAccount]
params:
  userId: { kind: { type: text, maxLength: 128 }, required: true, description: The taker }
results:
  available:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  inTransfer: { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.payout.system]
---*/
-- The taker's lines only (the party, dimension 1, is indexed). One statement: what is committed, and what the calling
-- transaction posted itself (a transfer recomputes the cache after its own posting).
SELECT
    coalesce(sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2120' THEN CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT'
        THEN e.{{LedgerEntry.amount}} ELSE -e.{{LedgerEntry.amount}} END END), 0) AS available,
    coalesce(sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2130' THEN CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT'
        THEN e.{{LedgerEntry.amount}} ELSE -e.{{LedgerEntry.amount}} END END), 0) AS inTransfer
FROM {{LedgerEntry}} e
JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
WHERE e.{{LedgerEntry.dimension1}} = CAST(:userId AS text)
  AND a.{{LedgerAccount.accountCode}} IN ('2120', '2130')
