/*---
id: qb.admin.publication-funds
description: Each publication's sponsor's funds - whether it is short and the rewards waiting for money (finance only)
entities: [QbPublication, QbWallet]
datasets:
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbWallet: urn:jabiz:dataset:default:QbWallet
results:
  publicationId: { from: QbPublication.publicationId }
  publisherKind: { from: QbPublication.publisherKind }
  publisherId:   { from: QbPublication.publisherId }
  status:        { from: QbPublication.status }
  fundsShort:    { kind: { type: bool } }
  pendingCount:  { from: QbWallet.pendingCount }
  pendingAmount: { from: QbWallet.pendingAmount }
list:
  filters: [publicationId, publisherKind, publisherId, status, fundsShort]
  sorts:   [publicationId]
  defaultSort: { field: publicationId, asc: true }
  key: [publicationId]
permissions: [qb.admin.finance.read]
---*/
-- The funds part of qb.sponsor.publications, for every sponsor: money is the finance administrators' (Q9a), so the
-- review queue (qb.admin.publications) does not carry it. Short: underfunded, or live while rewards wait or the
-- available balance (balance - held) is below the highest reward.
SELECT
    p.{{QbPublication.publicationId}} AS publicationId,
    p.{{QbPublication.publisherKind}}   AS publisherKind,
    p.{{QbPublication.publisherId}}     AS publisherId,
    p.{{QbPublication.status}}        AS status,
    (p.{{QbPublication.status}} = 'FUNDS_SHORT'
     OR (p.{{QbPublication.status}} = 'LIVE' AND (coalesce(w.{{QbWallet.pendingCount}}, 0) > 0
         OR coalesce(w.{{QbWallet.balance}} - w.{{QbWallet.held}}, 0) < p.{{QbPublication.maxReward}}))) AS fundsShort,
    coalesce(w.{{QbWallet.pendingCount}}, 0)  AS pendingCount,
    coalesce(w.{{QbWallet.pendingAmount}}, 0) AS pendingAmount
FROM {{QbPublication}} p
LEFT JOIN {{QbWallet}} w ON w.{{QbWallet.ownerId}} = p.{{QbPublication.publisherId}} AND w.{{QbWallet.kind}} = 'SPONSOR'
