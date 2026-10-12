/*---
id: qb.system.transfers-stale
description: Transfers in doubt - checking, or still sending after the cutoff - not asked about lately (the items of the job qb.transfer-stale)
entities: [QbTransfer]
datasets:
  QbTransfer: urn:jabiz:dataset:default:QbTransfer
params:
  sendingBefore: { like: QbTransfer.createdAt, required: true, description: The scheduled time less the wait before a sending transfer is asked about }
  askedBefore:   { like: QbTransfer.checkRequestedAt, required: true, description: The scheduled time less the wait before asking again }
results:
  transferId: { from: QbTransfer.transferId }
  createdAt:  { from: QbTransfer.createdAt }
list:
  sorts: [createdAt]
  defaultSort: { field: createdAt, asc: true }
  key: [transferId]
permissions: [qb.payout.system]
---*/
-- Through the partial index on the open transfers (V8). QB_TRANSFER_CHECK reads the transfer again under the taker's
-- wallet lock and leaves one settled meanwhile.
SELECT t.{{QbTransfer.transferId}} AS transferId, t.{{QbTransfer.createdAt}} AS createdAt
FROM {{QbTransfer}} t
WHERE (t.{{QbTransfer.status}} = 'CHECK'
       OR (t.{{QbTransfer.status}} = 'SENDING' AND t.{{QbTransfer.createdAt}} < :sendingBefore))
  AND (t.{{QbTransfer.checkRequestedAt}} IS NULL OR t.{{QbTransfer.checkRequestedAt}} < :askedBefore)
