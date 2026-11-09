/*---
id: qb.operator.fee-account
description: "F-M1: the account that pays the platform's fees, one sponsor's of the caller's operator (another operator's sponsor: no rows) - the fee credit, the cash and what open disputes hold of it, one row"
entities: [QbWallet, QbSponsorOperator, QbOperatorMember]
datasets:
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
params:
  sponsorId: { like: QbWallet.ownerId, required: true, description: "The sponsor (their user id)" }
results:
  feeCreditBalance: { kind: { type: monetary, currency: JPY, scale: 0 } }
  cashBalance:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  cashHeld:         { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.operator.use]
---*/
-- The wallets' caches (the daily reconciliation compares them with 2190 and 2160 and with the lots); 0 without a row.
SELECT
    coalesce(sum(w.{{QbWallet.balance}}) FILTER (WHERE w.{{QbWallet.kind}} = 'FEE_CREDIT'), 0) AS feeCreditBalance,
    coalesce(sum(w.{{QbWallet.balance}}) FILTER (WHERE w.{{QbWallet.kind}} = 'CASH'), 0)       AS cashBalance,
    coalesce(sum(w.{{QbWallet.held}}) FILTER (WHERE w.{{QbWallet.kind}} = 'CASH'), 0)          AS cashHeld
FROM (SELECT 1 AS one) base
LEFT JOIN {{QbWallet}} w ON w.{{QbWallet.ownerId}} = :sponsorId
    AND w.{{QbWallet.kind}} IN ('FEE_CREDIT', 'CASH')
WHERE true
  AND EXISTS (SELECT 1 FROM {{QbSponsorOperator}} a
              JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
                AND me.{{QbOperatorMember.operatorId}} = a.{{QbSponsorOperator.operatorId}}
              WHERE a.{{QbSponsorOperator.userId}} = :sponsorId)
