/*---
id: qb.operator.transfers
description: "The caller operator's transfers to its direct Sponsor (F-M4), the newest first: how much, when, by whom and the lead's note"
entities: [QbOperatorTransfer, QbOperatorMember]
datasets:
  QbOperatorTransfer: urn:jabiz:dataset:default:QbOperatorTransfer
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
results:
  transferId:    { from: QbOperatorTransfer.transferId }
  amount:        { from: QbOperatorTransfer.amount }
  note:          { from: QbOperatorTransfer.note }
  requestedBy:   { from: QbOperatorTransfer.requestedBy }
  transferredAt: { from: QbOperatorTransfer.transferredAt }
list:
  sorts:   [transferredAt, amount]
  defaultSort: { field: transferredAt, asc: false }
  key: [transferId]
permissions: [qb.operator.use]
---*/
-- The caller's operator's only: the membership dataset is the caller's own, current membership.
SELECT
    t.{{QbOperatorTransfer.transferId}} AS transferId,
    t.{{QbOperatorTransfer.amount}} AS amount,
    t.{{QbOperatorTransfer.note}} AS note,
    t.{{QbOperatorTransfer.requestedBy}} AS requestedBy,
    t.{{QbOperatorTransfer.transferredAt}} AS transferredAt
FROM {{QbOperatorTransfer}} t
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
  AND me.{{QbOperatorMember.operatorId}} = t.{{QbOperatorTransfer.operatorId}}
