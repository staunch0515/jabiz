/*---
id: qb.system.topups-bank-expired
description: Bank top-ups still waiting for money that were asked for before the cutoff (the items of the job qb.topup-bank-expire)
entities: [QbTopUp]
datasets:
  QbTopUp: urn:jabiz:dataset:default:QbTopUp
params:
  createdBefore: { like: QbTopUp.createdAt, required: true, description: The scheduled time less how long a request waits }
results:
  topUpId:   { from: QbTopUp.topUpId }
  createdAt: { from: QbTopUp.createdAt }
list:
  sorts: [createdAt]
  defaultSort: { field: createdAt, asc: true }
  key: [topUpId]
permissions: [qb.wallet.system]
---*/
-- Through the partial index on the waiting bank top-ups (V20). The job's process reads the top-up again under the
-- sponsor's wallet lock and leaves one that was recorded or withdrawn meanwhile.
SELECT t.{{QbTopUp.topUpId}} AS topUpId, t.{{QbTopUp.createdAt}} AS createdAt
FROM {{QbTopUp}} t
WHERE t.{{QbTopUp.status}} IN ('AWAITING', 'REPORTED')
  AND t.{{QbTopUp.createdAt}} < :createdBefore
