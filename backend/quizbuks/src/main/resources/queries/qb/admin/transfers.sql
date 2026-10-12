/*---
id: qb.admin.transfers
description: Every transfer (P-03) - the taker, amount, fee, state, review, Stripe's transfer and the account's last four characters
entities: [QbTransfer]
datasets:
  QbTransfer: urn:jabiz:dataset:default:QbTransfer
results:
  transferId:        { from: QbTransfer.transferId }
  userId:            { from: QbTransfer.userId }
  externalAccountId: { from: QbTransfer.externalAccountId }
  amount:            { from: QbTransfer.amount }
  fee:               { from: QbTransfer.fee }
  net:               { from: QbTransfer.net }
  status:            { from: QbTransfer.status }
  approvalRequestId: { from: QbTransfer.approvalRequestId }
  stripeTransferId:  { from: QbTransfer.stripeTransferId }
  reversedAmount:    { from: QbTransfer.reversedAmount }
  failureCode:       { from: QbTransfer.failureCode }
  createdAt:         { from: QbTransfer.createdAt }
  sentAt:            { from: QbTransfer.sentAt }
list:
  filters: [userId, status, createdAt, sentAt, amount, failureCode]
  sorts:   [createdAt, sentAt, amount]
  defaultSort: { field: createdAt, asc: false }
  key: [transferId]
permissions: [qb.admin.finance.read]
---*/
-- The account id is masked for everyone without qb.admin.finance.read (the platform masks it in SQL).
SELECT
    t.{{QbTransfer.transferId}}        AS transferId,
    t.{{QbTransfer.userId}}            AS userId,
    t.{{QbTransfer.externalAccountId}} AS externalAccountId,
    t.{{QbTransfer.amount}}            AS amount,
    t.{{QbTransfer.fee}}               AS fee,
    t.{{QbTransfer.net}}               AS net,
    t.{{QbTransfer.status}}            AS status,
    t.{{QbTransfer.approvalRequestId}} AS approvalRequestId,
    t.{{QbTransfer.stripeTransferId}}  AS stripeTransferId,
    t.{{QbTransfer.reversedAmount}}    AS reversedAmount,
    t.{{QbTransfer.failureCode}}       AS failureCode,
    t.{{QbTransfer.createdAt}}         AS createdAt,
    t.{{QbTransfer.sentAt}}            AS sentAt
FROM {{QbTransfer}} t
