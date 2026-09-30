/*---
id: logistics.freight_statements_due
description: Closed freight statements due on or before a day (the date kind, docs/design/02-metamodel.md section 1)
entities: [FreightStatement]
params:
  dueBy: { like: FreightStatement.dueDate, required: true, description: Statements due on or before this day }
results:
  statementMonth: { from: FreightStatement.statementMonth }
  dueDate:        { from: FreightStatement.dueDate }
  chargeCount:    { from: FreightStatement.chargeCount }
  totalAmount:    { from: FreightStatement.totalAmount }
list:
  filters: [statementMonth, dueDate]
  sorts:   [dueDate, statementMonth]
  defaultSort: { field: dueDate, asc: true }
  key: [statementMonth]
permissions: [logistics.freight.read]
report:
  period: { to: dueBy }
---*/
SELECT
    s.{{FreightStatement.statementMonth}} AS statementMonth,
    s.{{FreightStatement.dueDate}}        AS dueDate,
    s.{{FreightStatement.chargeCount}}    AS chargeCount,
    s.{{FreightStatement.totalAmount}}    AS totalAmount
FROM {{FreightStatement}} s
WHERE s.{{FreightStatement.closed}}
  AND s.{{FreightStatement.dueDate}} <= :dueBy
