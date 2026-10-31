/*---
id: qb.admin.operator-plans
description: "Each operator's plan changes (Q48e-2), the newest first: which plan from which month, why, who proposed it and whether another administrator approved it yet"
entities: [QbOperatorPlan, QbOperator]
datasets:
  QbOperatorPlan: urn:jabiz:dataset:default:QbOperatorPlan
  QbOperator: urn:jabiz:dataset:default:QbOperator
params:
  operatorId: { like: QbOperatorPlan.operatorId, description: Only this operator's }
results:
  planChangeId:      { from: QbOperatorPlan.planChangeId }
  operatorId:        { from: QbOperatorPlan.operatorId }
  operatorName:      { from: QbOperator.name }
  plan:              { from: QbOperatorPlan.plan }
  effectiveMonth:    { from: QbOperatorPlan.effectiveMonth }
  reason:            { from: QbOperatorPlan.reason }
  status:            { from: QbOperatorPlan.status }
  proposedBy:        { from: QbOperatorPlan.proposedBy }
  proposedAt:        { from: QbOperatorPlan.proposedAt }
  approvalRequestId: { from: QbOperatorPlan.approvalRequestId }
  decidedAt:         { from: QbOperatorPlan.decidedAt }
list:
  filters: [status]
  sorts:   [proposedAt, effectiveMonth]
  defaultSort: { field: proposedAt, asc: false }
  key: [planChangeId]
permissions: [qb.admin.operators.read]
---*/
SELECT
    c.{{QbOperatorPlan.planChangeId}} AS planChangeId,
    c.{{QbOperatorPlan.operatorId}} AS operatorId,
    o.{{QbOperator.name}} AS operatorName,
    c.{{QbOperatorPlan.plan}} AS plan,
    c.{{QbOperatorPlan.effectiveMonth}} AS effectiveMonth,
    c.{{QbOperatorPlan.reason}} AS reason,
    c.{{QbOperatorPlan.status}} AS status,
    c.{{QbOperatorPlan.proposedBy}} AS proposedBy,
    c.{{QbOperatorPlan.proposedAt}} AS proposedAt,
    c.{{QbOperatorPlan.approvalRequestId}} AS approvalRequestId,
    c.{{QbOperatorPlan.decidedAt}} AS decidedAt
FROM {{QbOperatorPlan}} c
JOIN {{QbOperator}} o ON o.{{QbOperator.operatorId}} = c.{{QbOperatorPlan.operatorId}}
WHERE (CAST(:operatorId AS text) IS NULL OR c.{{QbOperatorPlan.operatorId}} = :operatorId)
