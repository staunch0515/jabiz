/*---
id: qb.system.operator-settlements-due
description: The operators without a settlement of a month (the items of the job qb.operator-settlement), the suspended ones too (they are owed what their sponsors earned)
entities: [QbOperator, QbOperatorSettlement]
datasets:
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
params:
  month:     { like: QbOperatorSettlement.month, required: true, description: "The month (yyyy-MM, Japan's)" }
  periodEnd: { like: QbOperatorSettlement.periodEnd, required: true, description: "The month's end: operators created later have none" }
results:
  operatorId: { from: QbOperator.operatorId }
list:
  sorts: [operatorId]
  defaultSort: { field: operatorId, asc: true }
  key: [operatorId]
permissions: [qb.operator.settlement.system]
---*/
-- The item reads again under the operator's lock and does nothing when the month is settled meanwhile.
SELECT o.{{QbOperator.operatorId}} AS operatorId
FROM {{QbOperator}} o
WHERE o.{{QbOperator.createdAt}} < :periodEnd
  AND NOT EXISTS (SELECT 1 FROM {{QbOperatorSettlement}} s
                   WHERE s.{{QbOperatorSettlement.operatorId}} = o.{{QbOperator.operatorId}}
                     AND s.{{QbOperatorSettlement.month}} = :month)
