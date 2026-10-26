/*---
id: qb.system.recon-sponsor-pending
description: Sponsors whose cached pending rewards differ from their pending attempts (the daily reconciliation; differences only)
entities: [QbWallet, QbAttempt]
datasets:
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
results:
  partyId:        { kind: { type: text, maxLength: 128 } }
  expectedCount:  { kind: { type: numeric, precision: 19, scale: 0 } }
  actualCount:    { kind: { type: numeric, precision: 19, scale: 0 } }
  expectedAmount: { kind: { type: monetary, currency: JPY, scale: 0 } }
  actualAmount:   { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [partyId]
  defaultSort: { field: partyId, asc: true }
  key: [partyId]
permissions: [qb.wallet.system]
---*/
-- One statement, one snapshot (see qb.system.recon-sponsor-wallets). The pending attempts through the partial index of
-- the pending rewards (V6).
SELECT
    coalesce(w.w_owner, p.p_sponsor)      AS partyId,
    coalesce(p.p_count, 0)                AS expectedCount,
    coalesce(w.w_count, 0)                AS actualCount,
    coalesce(p.p_amount, 0)               AS expectedAmount,
    coalesce(w.w_amount, 0)               AS actualAmount
FROM (
    SELECT x.{{QbWallet.ownerId}} AS w_owner, x.{{QbWallet.pendingCount}} AS w_count,
           x.{{QbWallet.pendingAmount}} AS w_amount
    FROM {{QbWallet}} x
    WHERE x.{{QbWallet.kind}} = 'SPONSOR') w
FULL OUTER JOIN (
    SELECT m.{{QbAttempt.publisherId}} AS p_sponsor, count(*) AS p_count, sum(m.{{QbAttempt.reward}}) AS p_amount
    FROM {{QbAttempt}} m
    -- S0: the pending rewards of sponsors' wallets (a shop has no wallet).
    WHERE m.{{QbAttempt.rewardStatus}} = 'PENDING' AND m.{{QbAttempt.publisherKind}} = 'SPONSOR'
    GROUP BY m.{{QbAttempt.publisherId}}) p ON p.p_sponsor = w.w_owner
WHERE coalesce(p.p_count, 0) <> coalesce(w.w_count, 0)
   OR coalesce(p.p_amount, 0) <> coalesce(w.w_amount, 0)
