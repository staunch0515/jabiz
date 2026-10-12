/*---
id: qb.system.topups-stale
description: Open top-ups whose checkout expired before the cutoff without word from Stripe, not asked about lately (the items of the job qb.topup-stale)
entities: [QbTopUp]
datasets:
  QbTopUp: urn:jabiz:dataset:default:QbTopUp
params:
  expiredBefore: { like: QbTopUp.expiresAt, required: true, description: The scheduled time less the wait after a checkout's expiry }
  askedBefore:   { like: QbTopUp.checkRequestedAt, required: true, description: The scheduled time less the wait before asking again }
results:
  topUpId:   { from: QbTopUp.topUpId }
  expiresAt: { from: QbTopUp.expiresAt }
list:
  sorts: [expiresAt]
  defaultSort: { field: expiresAt, asc: true }
  key: [topUpId]
permissions: [qb.wallet.system]
---*/
-- Through the partial index on the expiry of unsettled top-ups (V7). The job's process reads the top-up again under the
-- sponsor's wallet lock and leaves one that was settled meanwhile.
SELECT t.{{QbTopUp.topUpId}} AS topUpId, t.{{QbTopUp.expiresAt}} AS expiresAt
FROM {{QbTopUp}} t
WHERE t.{{QbTopUp.status}} = 'OPEN'
  AND t.{{QbTopUp.checkoutSessionId}} IS NOT NULL
  AND t.{{QbTopUp.expiresAt}} < :expiredBefore
  AND (t.{{QbTopUp.checkRequestedAt}} IS NULL OR t.{{QbTopUp.checkRequestedAt}} < :askedBefore)
