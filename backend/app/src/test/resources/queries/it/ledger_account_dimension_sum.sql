/*---
id: it.ledger_account_dimension_sum
description: >-
  Everything ever posted to one account for one value of the first dimension, by direction: the sum an application
  keeps per owner (a wallet balance), served by ledger_entry_version_account_dimension_1_idx (V66, ROADMAP 17zz18)
entities: [LedgerEntry]
params:
  accountId: { like: LedgerEntry.accountId, required: true, description: the account }
  owner:     { like: LedgerEntry.dimension1, required: true, description: the value of the first dimension }
results:
  direction: { from: LedgerEntry.direction }
  total:     { from: LedgerEntry.amount }
list:
  key: [direction]
permissions: [ledger.read]
---*/
SELECT e.{{LedgerEntry.direction}} AS direction, SUM(e.{{LedgerEntry.amount}}) AS total
FROM {{LedgerEntry}} e
WHERE e.{{LedgerEntry.accountId}} = :accountId
  AND e.{{LedgerEntry.dimension1}} = :owner
GROUP BY e.{{LedgerEntry.direction}}
