/*---
id: it.jp_prices
description: Current prices of region JP (temporal entity through a scoped dataset)
entities: [ItPrice]
datasets: { ItPrice: "urn:jabiz:dataset:it:ItPriceJP" }
params:
  ids:    { like: ItPrice.priceId, list: true }
  minAmount: { like: ItPrice.amount }
results:
  priceId: { from: ItPrice.priceId }
  sku:     { from: ItPrice.sku }
  amount:  { from: ItPrice.amount }
  note:    { from: ItPrice.note }
list:
  filters: [sku, amount]
  sorts: [sku, amount]
  defaultSort: { field: sku, asc: true }
  key: [priceId]
permissions: [it.query]
---*/
SELECT p.{{ItPrice.priceId}} AS priceId,
       p.{{ItPrice.sku}}     AS sku,
       p.{{ItPrice.amount}}  AS amount,
       p.{{ItPrice.note}}    AS note
FROM {{ItPrice}} p
WHERE (CAST(:ids AS uuid[]) IS NULL OR p.{{ItPrice.priceId}} = ANY(:ids))
  AND (CAST(:minAmount AS numeric) IS NULL OR p.{{ItPrice.amount}} >= :minAmount)
