/*---
id: qb.admin.verify-overdrawn-wallets
description: >-
  The wallets the ledger shows below 0: a party whose balance on 2110, 2120, 2130, 2160 or 2190 is a debit; none when the
  ledger is sound. A restore check (operations.md section 2.4, DRILL_TEMPLATES): rows only when there is a problem
entities: [LedgerAccount, LedgerEntry]
results:
  accountCode: { kind: { type: text, maxLength: 50 } }
  partyId:     { kind: { type: text, maxLength: 128 } }
  overdrawn:   { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [accountCode, partyId]
  defaultSort: { field: accountCode, asc: true }
  key: [accountCode, partyId]
permissions: [qb.admin.finance.read]
---*/
-- The check NO_OVERDRAWN_WALLET of qb.admin.balances, over the whole ledger and by party: these accounts are
-- liabilities, so a party's debit less credit above 0 is a wallet below 0. Lines without a party are the check
-- SPONSOR_WALLETS / TAKER_WALLETS of that report.
SELECT p.accountCode, p.partyId, p.overdrawn
FROM (
    SELECT CAST(a.{{LedgerAccount.accountCode}} AS varchar(50)) AS accountCode,
           CAST(e.{{LedgerEntry.dimension1}} AS varchar(128))   AS partyId,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END)               AS overdrawn
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} IN ('2110', '2120', '2130', '2160', '2190')
      AND e.{{LedgerEntry.dimension1}} IS NOT NULL
    GROUP BY a.{{LedgerAccount.accountCode}}, e.{{LedgerEntry.dimension1}}) p
WHERE p.overdrawn > 0
