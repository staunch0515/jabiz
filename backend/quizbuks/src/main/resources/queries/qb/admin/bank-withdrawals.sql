/*---
id: qb.admin.bank-withdrawals
description: The withdrawals to bank accounts (Q13b), newest first - the amount, the account as it was asked for (the number masked), the review, and how finance paid it or why it came back - for finance
entities: [QbTransfer]
datasets:
  QbTransfer: urn:jabiz:dataset:default:QbTransfer
results:
  transferId:        { from: QbTransfer.transferId }
  userId:            { from: QbTransfer.userId }
  amount:            { from: QbTransfer.amount }
  fee:               { from: QbTransfer.fee }
  net:               { from: QbTransfer.net }
  status:            { from: QbTransfer.status }
  createdAt:         { from: QbTransfer.createdAt }
  bankCode:          { from: QbTransfer.bankCode }
  bankName:          { from: QbTransfer.bankName }
  branchCode:        { from: QbTransfer.branchCode }
  branchName:        { from: QbTransfer.branchName }
  accountType:       { from: QbTransfer.accountType }
  accountHolder:     { from: QbTransfer.accountHolder }
  approvalRequestId: { from: QbTransfer.approvalRequestId }
  paidOn:            { from: QbTransfer.paidOn }
  paidReference:     { from: QbTransfer.paidReference }
  paidBy:            { from: QbTransfer.paidBy }
  sentAt:            { from: QbTransfer.sentAt }
  failureCode:       { from: QbTransfer.failureCode }
  failureMessage:    { from: QbTransfer.failureMessage }
  failedAt:          { from: QbTransfer.failedAt }
list:
  filters: [transferId, userId, status, createdAt]
  sorts:   [createdAt, sentAt, amount, status]
  defaultSort: { field: createdAt, asc: false }
  key: [transferId]
permissions: [qb.admin.finance.read]
---*/
-- Bank withdrawals only (the partial index qb_transfer_bank_idx, V21). The account number is not here: finance
-- reveals it on the transfer, one value at a time and recorded, when paying (qb.payout.bank.reveal).
SELECT
    t.{{QbTransfer.transferId}}        AS transferId,
    t.{{QbTransfer.userId}}            AS userId,
    t.{{QbTransfer.amount}}            AS amount,
    t.{{QbTransfer.fee}}               AS fee,
    t.{{QbTransfer.net}}               AS net,
    t.{{QbTransfer.status}}            AS status,
    t.{{QbTransfer.createdAt}}         AS createdAt,
    t.{{QbTransfer.bankCode}}          AS bankCode,
    t.{{QbTransfer.bankName}}          AS bankName,
    t.{{QbTransfer.branchCode}}        AS branchCode,
    t.{{QbTransfer.branchName}}        AS branchName,
    t.{{QbTransfer.accountType}}       AS accountType,
    t.{{QbTransfer.accountHolder}}     AS accountHolder,
    t.{{QbTransfer.approvalRequestId}} AS approvalRequestId,
    t.{{QbTransfer.paidOn}}            AS paidOn,
    t.{{QbTransfer.paidReference}}     AS paidReference,
    t.{{QbTransfer.paidBy}}            AS paidBy,
    t.{{QbTransfer.sentAt}}            AS sentAt,
    t.{{QbTransfer.failureCode}}       AS failureCode,
    t.{{QbTransfer.failureMessage}}    AS failureMessage,
    t.{{QbTransfer.failedAt}}          AS failedAt
FROM {{QbTransfer}} t
WHERE t.{{QbTransfer.method}} = 'BANK'
