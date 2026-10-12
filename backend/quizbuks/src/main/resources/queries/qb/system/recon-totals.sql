/*---
id: qb.system.recon-totals
description: Whether account 2110 adds up to the sponsors' wallets and the whole ledger to zero (the daily reconciliation; differences only)
entities: [QbWallet, LedgerEntry, LedgerAccount]
datasets:
  QbWallet: urn:jabiz:dataset:default:QbWallet
results:
  checkCode: { kind: { type: text, maxLength: 30 } }
  expected:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  actual:    { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [checkCode]
  defaultSort: { field: checkCode, asc: true }
  key: [checkCode]
permissions: [qb.wallet.system]
---*/
-- One statement, one snapshot. TOTAL_2110: account 2110 (credit less debit) against every sponsor wallet's cached
-- balance. LEDGER_ZERO: every line's debit less credit, which balances to 0.
SELECT CAST('TOTAL_2110' AS varchar(30)) AS checkCode, l.l_total AS expected, w.w_total AS actual
FROM (
    SELECT coalesce(sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END), 0) AS l_total
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2110') l
CROSS JOIN (
    SELECT coalesce(sum(x.{{QbWallet.balance}}), 0) AS w_total
    FROM {{QbWallet}} x
    WHERE x.{{QbWallet.kind}} = 'SPONSOR') w
WHERE l.l_total <> w.w_total
UNION ALL
SELECT CAST('LEDGER_ZERO' AS varchar(30)) AS checkCode, CAST(0 AS numeric) AS expected, z.z_total AS actual
FROM (
    SELECT coalesce(sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END), 0) AS z_total
    FROM {{LedgerEntry}} e) z
WHERE z.z_total <> 0
