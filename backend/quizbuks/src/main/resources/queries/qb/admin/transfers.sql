/*---
id: qb.admin.transfers
description: Every transfer (P-03) - the taker, amount, fee, state, review, Stripe's transfer and the account's last four characters
entities: [QbTransfer]
datasets:
  QbTransfer: urn:jabiz:dataset:default:QbTransfer
params:
  staleBefore: { like: QbTransfer.createdAt, description: "A transfer still sending that was created before this time is in doubt (stale; the finance pages: an hour ago)" }
results:
  transferId:        { from: QbTransfer.transferId }
  userId:            { from: QbTransfer.userId }
  externalAccountId: { from: QbTransfer.externalAccountId }
  method:            { from: QbTransfer.method }
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
  stale:             { kind: { type: bool } }
  held:              { kind: { type: bool } }
  releasedAt:        { from: QbTransfer.releasedAt }
  releasedBy:        { from: QbTransfer.releasedBy }
list:
  filters: [transferId, userId, method, status, createdAt, sentAt, amount, failureCode, stale, held]
  sorts:   [createdAt, sentAt, amount]
  defaultSort: { field: createdAt, asc: false }
  key: [transferId]
permissions: [qb.admin.finance.read]
---*/
-- The account id is masked for everyone without qb.admin.finance.read (the platform masks it in SQL). Stale: in doubt,
-- CHECK, or SENDING since before staleBefore (without it, only CHECK); the hourly job asks Stripe about those
-- (qb.system.transfers-stale). Held: rejected or reversed and not yet released, which keeps the taker's next transfers
-- back until finance releases them (QB_TRANSFER_HOLD_RELEASE).
SELECT
    t.{{QbTransfer.transferId}}        AS transferId,
    t.{{QbTransfer.userId}}            AS userId,
    t.{{QbTransfer.externalAccountId}} AS externalAccountId,
    t.{{QbTransfer.method}}            AS method,
    t.{{QbTransfer.amount}}            AS amount,
    t.{{QbTransfer.fee}}               AS fee,
    t.{{QbTransfer.net}}               AS net,
    t.{{QbTransfer.status}}            AS status,
    t.{{QbTransfer.approvalRequestId}} AS approvalRequestId,
    t.{{QbTransfer.stripeTransferId}}  AS stripeTransferId,
    t.{{QbTransfer.reversedAmount}}    AS reversedAmount,
    t.{{QbTransfer.failureCode}}       AS failureCode,
    t.{{QbTransfer.createdAt}}         AS createdAt,
    t.{{QbTransfer.sentAt}}            AS sentAt,
    (t.{{QbTransfer.status}} = 'CHECK' OR (t.{{QbTransfer.status}} = 'SENDING'
        AND t.{{QbTransfer.createdAt}} < CAST(:staleBefore AS timestamptz))) IS TRUE AS stale,
    (t.{{QbTransfer.releasedAt}} IS NULL AND (t.{{QbTransfer.status}} = 'REJECTED'
        OR t.{{QbTransfer.reversedAmount}} > 0)) AS held,
    t.{{QbTransfer.releasedAt}}        AS releasedAt,
    t.{{QbTransfer.releasedBy}}        AS releasedBy
FROM {{QbTransfer}} t
