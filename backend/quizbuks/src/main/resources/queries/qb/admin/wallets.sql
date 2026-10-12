/*---
id: qb.admin.wallets
description: The wallets with their balance and pending rewards (sponsors), or on its way out (takers, Q6b), largest balance first
entities: [QbWallet, QbSponsor]
datasets:
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  kind: { like: QbWallet.kind, description: Only the wallets of this kind (SPONSOR or TAKER) }
results:
  walletId:      { from: QbWallet.walletId }
  ownerId:       { from: QbWallet.ownerId }
  kind:          { from: QbWallet.kind }
  company:       { from: QbSponsor.company }
  balance:       { from: QbWallet.balance }
  held:          { from: QbWallet.held }
  pendingCount:  { from: QbWallet.pendingCount }
  pendingAmount: { from: QbWallet.pendingAmount }
  pendingOut:    { from: QbWallet.pendingOut }
list:
  filters: [ownerId, kind, balance, pendingCount]
  sorts:   [balance, pendingCount, pendingAmount]
  defaultSort: { field: balance, asc: false }
  key: [walletId]
permissions: [qb.admin.finance.read]
---*/
-- The cache of the balances; the ledger is the truth (2110 by party for sponsors, 2120 and 2130 for takers), reconciled
-- daily.
SELECT
    w.{{QbWallet.walletId}}      AS walletId,
    w.{{QbWallet.ownerId}}       AS ownerId,
    w.{{QbWallet.kind}}          AS kind,
    s.{{QbSponsor.company}}      AS company,
    w.{{QbWallet.balance}}       AS balance,
    w.{{QbWallet.held}}          AS held,
    w.{{QbWallet.pendingCount}}  AS pendingCount,
    w.{{QbWallet.pendingAmount}} AS pendingAmount,
    w.{{QbWallet.pendingOut}}    AS pendingOut
FROM {{QbWallet}} w
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = w.{{QbWallet.ownerId}}
WHERE (CAST(:kind AS text) IS NULL OR w.{{QbWallet.kind}} = CAST(:kind AS text))
