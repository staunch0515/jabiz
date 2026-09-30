/*---
id: it.current_prices
description: Prices through a dataset that shows the current state only (no time travel)
entities: [ItPrice]
datasets: { ItPrice: "urn:jabiz:dataset:it:ItPriceCurrent" }
results:
  sku:    { from: ItPrice.sku }
  amount: { from: ItPrice.amount }
list:
  filters: [sku]
  sorts: [sku]
  defaultSort: { field: sku, asc: true }
permissions: [it.query]
---*/
SELECT p.{{ItPrice.sku}} AS sku, p.{{ItPrice.amount}} AS amount
FROM {{ItPrice}} p
