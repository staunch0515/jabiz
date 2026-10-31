/*---
id: qb.operator.settlements
description: The caller operator's monthly settlements (Q48b), the newest month first; not who recorded the payment nor its approval
entities: [QbOperatorSettlement, QbOperatorMember]
datasets:
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
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
  plan: { from: QbOperatorSettlement.plan }
  fixedFee: { from: QbOperatorSettlement.fixedFee }
  carriedIn: { from: QbOperatorSettlement.carriedIn }
  payable: { from: QbOperatorSettlement.payable }
  carriedOut: { from: QbOperatorSettlement.carriedOut }
  status: { from: QbOperatorSettlement.status }
  documentNo: { from: QbOperatorSettlement.documentNo }
  issuedAt: { from: QbOperatorSettlement.issuedAt }
  paidOn: { from: QbOperatorSettlement.paidOn }
  paymentRecordedAt: { from: QbOperatorSettlement.paymentRecordedAt }
  paidAt: { from: QbOperatorSettlement.paidAt }
  revision: { from: QbOperatorSettlement.revision }
list:
  filters: [status, month]
  sorts:   [month, payable, status]
  defaultSort: { field: month, asc: false }
  key: [settlementId]
permissions: [qb.operator.use]
---*/
-- The caller's operator's only: the membership dataset is the caller's own, current membership.
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
    s.{{QbOperatorSettlement.plan}} AS plan,
    s.{{QbOperatorSettlement.fixedFee}} AS fixedFee,
    s.{{QbOperatorSettlement.carriedIn}} AS carriedIn,
    s.{{QbOperatorSettlement.payable}} AS payable,
    s.{{QbOperatorSettlement.carriedOut}} AS carriedOut,
    s.{{QbOperatorSettlement.status}} AS status,
    s.{{QbOperatorSettlement.documentNo}} AS documentNo,
    s.{{QbOperatorSettlement.issuedAt}} AS issuedAt,
    s.{{QbOperatorSettlement.paidOn}} AS paidOn,
    s.{{QbOperatorSettlement.paymentRecordedAt}} AS paymentRecordedAt,
    s.{{QbOperatorSettlement.paidAt}} AS paidAt,
    s.{{QbOperatorSettlement.revision}} AS revision
FROM {{QbOperatorSettlement}} s
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
  AND me.{{QbOperatorMember.operatorId}} = s.{{QbOperatorSettlement.operatorId}}
