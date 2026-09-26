/*---
id: commerce.stock_availability
description: Stock of each product in each warehouse, with what is still available for new orders
entities: [StockLevel, Warehouse, Product]
params:
  warehouseCode: { like: Warehouse.warehouseCode, description: Only this warehouse when given }
results:
  warehouseCode: { from: Warehouse.warehouseCode }
  sku:           { from: Product.sku }
  productName:   { from: Product.productName }
  onHand:        { from: StockLevel.onHand }
  reserved:      { from: StockLevel.reserved }
  available:     { kind: { type: numeric, precision: 13, scale: 0 } }
list:
  filters: [warehouseCode, sku, available]
  sorts:   [warehouseCode, sku, available, onHand]
  defaultSort: { field: sku, asc: true }
  key: [warehouseCode, sku]
permissions: [commerce.stock.read]
---*/
SELECT
    w.{{Warehouse.warehouseCode}}                          AS warehouseCode,
    p.{{Product.sku}}                                      AS sku,
    p.{{Product.productName}}                              AS productName,
    s.{{StockLevel.onHand}}                                AS onHand,
    s.{{StockLevel.reserved}}                              AS reserved,
    s.{{StockLevel.onHand}} - s.{{StockLevel.reserved}}    AS available
FROM {{StockLevel}} s
JOIN {{Warehouse}} w ON w.{{Warehouse.warehouseId}} = s.{{StockLevel.warehouseId}}
JOIN {{Product}} p   ON p.{{Product.productId}} = s.{{StockLevel.productId}}
WHERE (CAST(:warehouseCode AS text) IS NULL OR w.{{Warehouse.warehouseCode}} = :warehouseCode)
