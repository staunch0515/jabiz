/*---
id: it.prices_at
description: Prices at the point in time its parameters give (docs/design/19-reports.md section 2.2)
entities: [ItPrice]
params:
  at:   { kind: { type: temporal, role: EVENT_TIME } }
  seen: { kind: { type: temporal, role: EVENT_TIME } }
results:
  sku:    { from: ItPrice.sku }
  amount: { from: ItPrice.amount }
list:
  filters: [sku]
  sorts: [sku]
  defaultSort: { field: sku, asc: true }
permissions: [it.query]
timeSlice: { asOf: at, knownAt: seen }
---*/
SELECT p.{{ItPrice.sku}} AS sku, p.{{ItPrice.amount}} AS amount
FROM {{ItPrice}} p
WHERE (CAST(:at AS timestamptz) IS NULL OR CAST(:seen AS timestamptz) IS NULL OR true)
