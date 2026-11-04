/*---
id: qb.system.entry-fees-due
description: The sponsors with entry fees not yet settled into their cash account (account 2170 above 0 for them, Q88b; the items of the job qb.entry-fee-settle)
entities: [LedgerEntry, LedgerAccount]
results:
  sponsorId: { kind: { type: text, maxLength: 128 } }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.wallet.system]
---*/
-- 2170 is a liability: credit less debit is what the platform still owes the sponsor. The item reads it again under
-- the sponsor's wallet lock and settles what it sees then.
SELECT CAST(e.{{LedgerEntry.dimension1}} AS varchar(128)) AS sponsorId
FROM {{LedgerEntry}} e
JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
WHERE a.{{LedgerAccount.accountCode}} = '2170'
GROUP BY e.{{LedgerEntry.dimension1}}
HAVING sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END) > 0
