/*---
id: qb.admin.operator-settlements
description: The operators' monthly settlements (Q48b) with the operator's name, the newest month first
entities: [QbOperatorSettlement, QbOperator]
datasets:
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
  QbOperator: urn:jabiz:dataset:default:QbOperator
params:
  settlementId: { like: QbOperatorSettlement.settlementId, description: Only this settlement }
  operatorId:   { like: QbOperatorSettlement.operatorId, description: Only this operator's }
  status:       { like: QbOperatorSettlement.status, description: Only settlements in this state }
results:
  settlementId: { from: QbOperatorSettlement.settlementId }
  operatorId: { from: QbOperatorSettlement.operatorId }
  month: { from: QbOperatorSettlement.month }
  periodStart: { from: QbOperatorSettlement.periodStart }
  periodEnd: { from: QbOperatorSettlement.periodEnd }
  revenue: { from: QbOperatorSettlement.revenue }
  platformShare: { from: QbOperatorSettlement.platformShare }
  operatorShare: { from: QbOperatorSettlement.operatorShare }
  prospectingCost: { from: QbOperatorSettlement.prospectingCost }
  carriedIn: { from: QbOperatorSettlement.carriedIn }
  payable: { from: QbOperatorSettlement.payable }
  carriedOut: { from: QbOperatorSettlement.carriedOut }
  status: { from: QbOperatorSettlement.status }
  documentNo: { from: QbOperatorSettlement.documentNo }
  documentRunId: { from: QbOperatorSettlement.documentRunId }
  issuedAt: { from: QbOperatorSettlement.issuedAt }
  paidOn: { from: QbOperatorSettlement.paidOn }
  bankReference: { from: QbOperatorSettlement.bankReference }
  paymentRecordedBy: { from: QbOperatorSettlement.paymentRecordedBy }
  paymentRecordedAt: { from: QbOperatorSettlement.paymentRecordedAt }
  approvalRequestId: { from: QbOperatorSettlement.approvalRequestId }
  paidAt: { from: QbOperatorSettlement.paidAt }
  revision: { from: QbOperatorSettlement.revision }
  operatorName: { from: QbOperator.name }
list:
  filters: [status, month]
  sorts:   [month, payable, status]
  defaultSort: { field: month, asc: false }
  key: [settlementId]
permissions: [qb.admin.finance.read]
---*/
SELECT
    s.{{QbOperatorSettlement.settlementId}} AS settlementId,
    s.{{QbOperatorSettlement.operatorId}} AS operatorId,
    s.{{QbOperatorSettlement.month}} AS month,
    s.{{QbOperatorSettlement.periodStart}} AS periodStart,
    s.{{QbOperatorSettlement.periodEnd}} AS periodEnd,
    s.{{QbOperatorSettlement.revenue}} AS revenue,
    s.{{QbOperatorSettlement.platformShare}} AS platformShare,
    s.{{QbOperatorSettlement.operatorShare}} AS operatorShare,
    s.{{QbOperatorSettlement.prospectingCost}} AS prospectingCost,
    s.{{QbOperatorSettlement.carriedIn}} AS carriedIn,
    s.{{QbOperatorSettlement.payable}} AS payable,
    s.{{QbOperatorSettlement.carriedOut}} AS carriedOut,
    s.{{QbOperatorSettlement.status}} AS status,
    s.{{QbOperatorSettlement.documentNo}} AS documentNo,
    s.{{QbOperatorSettlement.documentRunId}} AS documentRunId,
    s.{{QbOperatorSettlement.issuedAt}} AS issuedAt,
    s.{{QbOperatorSettlement.paidOn}} AS paidOn,
    s.{{QbOperatorSettlement.bankReference}} AS bankReference,
    s.{{QbOperatorSettlement.paymentRecordedBy}} AS paymentRecordedBy,
    s.{{QbOperatorSettlement.paymentRecordedAt}} AS paymentRecordedAt,
    s.{{QbOperatorSettlement.approvalRequestId}} AS approvalRequestId,
    s.{{QbOperatorSettlement.paidAt}} AS paidAt,
    s.{{QbOperatorSettlement.revision}} AS revision,
    o.{{QbOperator.name}} AS operatorName
FROM {{QbOperatorSettlement}} s
JOIN {{QbOperator}} o ON o.{{QbOperator.operatorId}} = s.{{QbOperatorSettlement.operatorId}}
WHERE (CAST(:settlementId AS text) IS NULL OR s.{{QbOperatorSettlement.settlementId}} = :settlementId)
  AND (CAST(:operatorId AS text) IS NULL OR s.{{QbOperatorSettlement.operatorId}} = :operatorId)
  AND (CAST(:status AS text) IS NULL OR s.{{QbOperatorSettlement.status}} = :status)
