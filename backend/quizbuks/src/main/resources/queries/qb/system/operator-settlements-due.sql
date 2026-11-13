/*---
id: qb.system.operator-settlements-due
description: "Each operator's earliest month, earlier than the current one, without a settlement and whose previous month is settled (the items of the daily job qb.operator-settlement; the suspended operators too, they are owed what their sponsors earned)"
entities: [QbOperator, QbOperatorSettlement]
datasets:
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
params:
  current: { like: QbOperatorSettlement.month, required: true, description: "The month of the run (yyyy-MM, the fiscal year's zone): earlier months only" }
  zone:    { kind: { type: text, maxLength: 64 }, required: true, description: "The fiscal year's zone (a region name or UTC)" }
results:
  operatorId: { from: QbOperator.operatorId }
  month:      { from: QbOperatorSettlement.month }
list:
  sorts: [operatorId]
  defaultSort: { field: operatorId, asc: true }
  key: [operatorId]
permissions: [qb.operator.settlement.system]
---*/
-- F-M12 b (plan §3.1): an operator's earliest month without a settlement, as qb.system.fee-invoices-due takes a sponsor's
-- earliest month: a month that is stuck stays the one picked until it is settled, and the next day's run takes the one
-- after it. An operator created after a month's end has no item for that month (its months start with the one it was
-- created in). Condition 1 of the plan: the previous month has its row (unless the operator was created in or after
-- this month); it never waits for a deadline. The conditions on the steps before it (the distribution fees, the
-- invoices) need the controlled setting of how long to wait, which a template cannot read: the item checks them
-- (qb.system.operator-settlement-readiness). The item reads again under the operator's lock and does nothing when the
-- month is settled meanwhile.
WITH months AS (
    SELECT o.{{QbOperator.operatorId}} AS operator_id,
           to_char(m.start_of_month, 'YYYY-MM') AS month,
           (m.start_of_month = date_trunc('month', o.{{QbOperator.createdAt}} AT TIME ZONE :zone)) AS first_month
    FROM {{QbOperator}} o
    CROSS JOIN LATERAL generate_series(date_trunc('month', o.{{QbOperator.createdAt}} AT TIME ZONE :zone),
                                       CAST(CAST(:current AS text) || '-01' AS timestamp) - interval '1 month',
                                       interval '1 month') AS m(start_of_month)
)
SELECT m.operator_id AS operatorId, min(m.month) AS month
FROM months m
WHERE NOT EXISTS (SELECT 1 FROM {{QbOperatorSettlement}} s
                   WHERE s.{{QbOperatorSettlement.operatorId}} = m.operator_id
                     AND s.{{QbOperatorSettlement.month}} = m.month)
  AND (m.first_month
       OR EXISTS (SELECT 1 FROM {{QbOperatorSettlement}} p
                   WHERE p.{{QbOperatorSettlement.operatorId}} = m.operator_id
                     AND p.{{QbOperatorSettlement.month}} = to_char(CAST(m.month || '-01' AS date) - interval '1 month', 'YYYY-MM')))
GROUP BY m.operator_id
