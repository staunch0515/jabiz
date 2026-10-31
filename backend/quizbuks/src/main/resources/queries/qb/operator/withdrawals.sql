/*---
id: qb.operator.withdrawals
description: "The caller operator's withdrawals (Q48e-2), the newest first: how much, to which account, how far it got and why it was rejected; not who recorded the payment nor its approval"
entities: [QbOperatorWithdrawal, QbOperatorMember]
datasets:
  QbOperatorWithdrawal: urn:jabiz:dataset:default:QbOperatorWithdrawal
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
results:
  withdrawalId:  { from: QbOperatorWithdrawal.withdrawalId }
  amount:        { from: QbOperatorWithdrawal.amount }
  status:        { from: QbOperatorWithdrawal.status }
  requestedAt:   { from: QbOperatorWithdrawal.requestedAt }
  bankName:      { from: QbOperatorWithdrawal.bankName }
  accountNumber: { from: QbOperatorWithdrawal.accountNumber }
  paidOn:        { from: QbOperatorWithdrawal.paidOn }
  rejectReason:  { from: QbOperatorWithdrawal.rejectReason }
  closedAt:      { from: QbOperatorWithdrawal.closedAt }
list:
  filters: [status]
  sorts:   [requestedAt, amount, status]
  defaultSort: { field: requestedAt, asc: false }
  key: [withdrawalId]
permissions: [qb.operator.use]
---*/
-- The caller's operator's only: the membership dataset is the caller's own, current membership.
SELECT
    w.{{QbOperatorWithdrawal.withdrawalId}} AS withdrawalId,
    w.{{QbOperatorWithdrawal.amount}} AS amount,
    w.{{QbOperatorWithdrawal.status}} AS status,
    w.{{QbOperatorWithdrawal.requestedAt}} AS requestedAt,
    w.{{QbOperatorWithdrawal.bankName}} AS bankName,
    w.{{QbOperatorWithdrawal.accountNumber}} AS accountNumber,
    w.{{QbOperatorWithdrawal.paidOn}} AS paidOn,
    w.{{QbOperatorWithdrawal.rejectReason}} AS rejectReason,
    w.{{QbOperatorWithdrawal.closedAt}} AS closedAt
FROM {{QbOperatorWithdrawal}} w
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
  AND me.{{QbOperatorMember.operatorId}} = w.{{QbOperatorWithdrawal.operatorId}}
