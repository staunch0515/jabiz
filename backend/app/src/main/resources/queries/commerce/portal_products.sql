/*---
id: commerce.portal_products
description: What a customer can order in the portal, and where from (docs/guide/app-spa.md)
entities: [Product, Warehouse, StockLevel]
results:
  warehouseCode: { from: Warehouse.warehouseCode }
  sku:           { from: Product.sku }
  productName:   { from: Product.productName }
  unitPrice:     { from: Product.unitPrice }
list:
  sorts:   [warehouseCode, sku, productName]
  defaultSort: { field: sku, asc: true }
  key: [warehouseCode, sku]
permissions: [commerce.portal.order]
---*/
SELECT
    w.{{Warehouse.warehouseCode}} AS warehouseCode,
    p.{{Product.sku}}             AS sku,
    p.{{Product.productName}}     AS productName,
    p.{{Product.unitPrice}}       AS unitPrice
FROM {{StockLevel}} s
JOIN {{Warehouse}} w ON w.{{Warehouse.warehouseId}} = s.{{StockLevel.warehouseId}}
JOIN {{Product}} p   ON p.{{Product.productId}} = s.{{StockLevel.productId}}
WHERE w.{{Warehouse.active}} AND p.{{Product.active}}
  AND s.{{StockLevel.onHand}} - s.{{StockLevel.reserved}} > 0
