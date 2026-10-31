/*---
id: qb.admin.operator-withdrawals
description: "The operators' withdrawals (Q48e-2) with the operator's name and the bank account they go to (the number masked without qb.payout.bank.reveal), the newest first: finance records the payment, another administrator approves it"
entities: [QbOperatorWithdrawal, QbOperator]
datasets:
  QbOperatorWithdrawal: urn:jabiz:dataset:default:QbOperatorWithdrawal
  QbOperator: urn:jabiz:dataset:default:QbOperator
params:
  withdrawalId: { like: QbOperatorWithdrawal.withdrawalId, description: Only this withdrawal }
  operatorId:   { like: QbOperatorWithdrawal.operatorId, description: Only this operator's }
  status:       { like: QbOperatorWithdrawal.status, description: Only withdrawals in this state }
results:
  withdrawalId:      { from: QbOperatorWithdrawal.withdrawalId }
  operatorId:        { from: QbOperatorWithdrawal.operatorId }
  operatorName:      { from: QbOperator.name }
  amount:            { from: QbOperatorWithdrawal.amount }
  status:            { from: QbOperatorWithdrawal.status }
  requestedBy:       { from: QbOperatorWithdrawal.requestedBy }
  requestedAt:       { from: QbOperatorWithdrawal.requestedAt }
  bankCode:          { from: QbOperatorWithdrawal.bankCode }
  bankName:          { from: QbOperatorWithdrawal.bankName }
  branchCode:        { from: QbOperatorWithdrawal.branchCode }
  branchName:        { from: QbOperatorWithdrawal.branchName }
  accountType:       { from: QbOperatorWithdrawal.accountType }
  accountNumber:     { from: QbOperatorWithdrawal.accountNumber }
  accountHolder:     { from: QbOperatorWithdrawal.accountHolder }
  paidOn:            { from: QbOperatorWithdrawal.paidOn }
  bankReference:     { from: QbOperatorWithdrawal.bankReference }
  paymentRecordedBy: { from: QbOperatorWithdrawal.paymentRecordedBy }
  approvalRequestId: { from: QbOperatorWithdrawal.approvalRequestId }
  paidAt:            { from: QbOperatorWithdrawal.paidAt }
  rejectReason:      { from: QbOperatorWithdrawal.rejectReason }
  revision:          { from: QbOperatorWithdrawal.revision }
list:
  filters: [status, operatorId]
  sorts:   [requestedAt, amount, status]
  defaultSort: { field: requestedAt, asc: false }
  key: [withdrawalId]
permissions: [qb.admin.finance.read]
---*/
SELECT
    w.{{QbOperatorWithdrawal.withdrawalId}} AS withdrawalId,
    w.{{QbOperatorWithdrawal.operatorId}} AS operatorId,
    o.{{QbOperator.name}} AS operatorName,
    w.{{QbOperatorWithdrawal.amount}} AS amount,
    w.{{QbOperatorWithdrawal.status}} AS status,
    w.{{QbOperatorWithdrawal.requestedBy}} AS requestedBy,
    w.{{QbOperatorWithdrawal.requestedAt}} AS requestedAt,
    w.{{QbOperatorWithdrawal.bankCode}} AS bankCode,
    w.{{QbOperatorWithdrawal.bankName}} AS bankName,
    w.{{QbOperatorWithdrawal.branchCode}} AS branchCode,
    w.{{QbOperatorWithdrawal.branchName}} AS branchName,
    w.{{QbOperatorWithdrawal.accountType}} AS accountType,
    w.{{QbOperatorWithdrawal.accountNumber}} AS accountNumber,
    w.{{QbOperatorWithdrawal.accountHolder}} AS accountHolder,
    w.{{QbOperatorWithdrawal.paidOn}} AS paidOn,
    w.{{QbOperatorWithdrawal.bankReference}} AS bankReference,
    w.{{QbOperatorWithdrawal.paymentRecordedBy}} AS paymentRecordedBy,
    w.{{QbOperatorWithdrawal.approvalRequestId}} AS approvalRequestId,
    w.{{QbOperatorWithdrawal.paidAt}} AS paidAt,
    w.{{QbOperatorWithdrawal.rejectReason}} AS rejectReason,
    w.{{QbOperatorWithdrawal.revision}} AS revision
FROM {{QbOperatorWithdrawal}} w
JOIN {{QbOperator}} o ON o.{{QbOperator.operatorId}} = w.{{QbOperatorWithdrawal.operatorId}}
WHERE (CAST(:withdrawalId AS text) IS NULL OR w.{{QbOperatorWithdrawal.withdrawalId}} = :withdrawalId)
  AND (CAST(:operatorId AS text) IS NULL OR w.{{QbOperatorWithdrawal.operatorId}} = :operatorId)
  AND (CAST(:status AS text) IS NULL OR w.{{QbOperatorWithdrawal.status}} = :status)
