/*---
id: qb.system.recon-taker-wallets
description: Takers whose cached wallet differs from accounts 2120 and 2130 for them (the daily reconciliation; differences only)
entities: [QbWallet, LedgerEntry, LedgerAccount]
datasets:
  QbWallet: urn:jabiz:dataset:default:QbWallet
results:
  partyId:         { kind: { type: text, maxLength: 128 } }
  expectedBalance: { kind: { type: monetary, currency: JPY, scale: 0 } }
  actualBalance:   { kind: { type: monetary, currency: JPY, scale: 0 } }
  expectedOut:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  actualOut:       { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [partyId]
  defaultSort: { field: partyId, asc: true }
  key: [partyId]
permissions: [qb.payout.system]
---*/
-- One statement, one snapshot. Unlike the sponsors', the takers' cache follows the ledger by events, seconds later
-- (D-Q6-11): a difference here may only be one not synced yet, so the reconciliation counts a taker only when two runs
-- in a row find them (ReconChecks.takers) and asks for a resync meanwhile. A taker with lines but no wallet, or a wallet
-- without lines, counts as 0 on the missing side.
SELECT
    coalesce(w.w_owner, l.l_party) AS partyId,
    coalesce(l.l_balance, 0)       AS expectedBalance,
    coalesce(w.w_balance, 0)       AS actualBalance,
    coalesce(l.l_out, 0)           AS expectedOut,
    coalesce(w.w_out, 0)           AS actualOut
FROM (
    SELECT x.{{QbWallet.ownerId}} AS w_owner, x.{{QbWallet.balance}} AS w_balance, x.{{QbWallet.pendingOut}} AS w_out
    FROM {{QbWallet}} x
    WHERE x.{{QbWallet.kind}} = 'TAKER') w
FULL OUTER JOIN (
    SELECT e.{{LedgerEntry.dimension1}} AS l_party,
           sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2120' THEN CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT'
               THEN e.{{LedgerEntry.amount}} ELSE -e.{{LedgerEntry.amount}} END ELSE 0 END) AS l_balance,
           sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2130' THEN CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT'
               THEN e.{{LedgerEntry.amount}} ELSE -e.{{LedgerEntry.amount}} END ELSE 0 END) AS l_out
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} IN ('2120', '2130')
    GROUP BY e.{{LedgerEntry.dimension1}}) l ON l.l_party = w.w_owner
WHERE coalesce(l.l_balance, 0) <> coalesce(w.w_balance, 0)
   OR coalesce(l.l_out, 0) <> coalesce(w.w_out, 0)
