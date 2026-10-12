/*---
id: qb.system.recon-sponsor-wallets
description: Sponsors whose cached balance differs from account 2110 for them (the daily reconciliation; differences only)
entities: [QbWallet, LedgerEntry, LedgerAccount]
datasets:
  QbWallet: urn:jabiz:dataset:default:QbWallet
results:
  partyId:  { kind: { type: text, maxLength: 128 } }
  expected: { kind: { type: monetary, currency: JPY, scale: 0 } }
  actual:   { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [partyId]
  defaultSort: { field: partyId, asc: true }
  key: [partyId]
permissions: [qb.wallet.system]
---*/
-- One statement, so one snapshot: a settlement posts and changes the cache in one transaction, and is either wholly in
-- this comparison or wholly not. A sponsor with lines but no wallet, or a wallet without lines, counts as 0 on the
-- missing side. Only the rows that differ come back (the full list grows with the sponsors; platform gap 25).
SELECT
    coalesce(w.w_owner, l.l_party) AS partyId,
    coalesce(l.l_balance, 0)       AS expected,
    coalesce(w.w_balance, 0)       AS actual
FROM (
    SELECT x.{{QbWallet.ownerId}} AS w_owner, x.{{QbWallet.balance}} AS w_balance
    FROM {{QbWallet}} x
    WHERE x.{{QbWallet.kind}} = 'SPONSOR') w
FULL OUTER JOIN (
    SELECT e.{{LedgerEntry.dimension1}} AS l_party,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS l_balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2110'
    GROUP BY e.{{LedgerEntry.dimension1}}) l ON l.l_party = w.w_owner
WHERE coalesce(l.l_balance, 0) <> coalesce(w.w_balance, 0)
