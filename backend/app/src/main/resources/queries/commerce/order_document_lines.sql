/*---
id: commerce.order_document_lines
description: The lines of one sales order as its confirmation shows them, products named as at the time read
entities: [SalesOrderLine, Product]
params:
  orderId: { like: SalesOrderLine.orderId, required: true, description: The order }
results:
  lineNo:      { from: SalesOrderLine.lineNo }
  sku:         { from: Product.sku }
  productName: { from: Product.productName }
  quantity:    { from: SalesOrderLine.quantity }
  unitPrice:   { from: SalesOrderLine.unitPrice }
  lineAmount:  { from: SalesOrderLine.lineAmount }
list:
  sorts: [lineNo]
  defaultSort: { field: lineNo, asc: true }
  key: [lineNo]
permissions: [commerce.order.read]
---*/
SELECT
    l.{{SalesOrderLine.lineNo}}     AS lineNo,
    p.{{Product.sku}}               AS sku,
    p.{{Product.productName}}       AS productName,
    l.{{SalesOrderLine.quantity}}   AS quantity,
    l.{{SalesOrderLine.unitPrice}}  AS unitPrice,
    l.{{SalesOrderLine.lineAmount}} AS lineAmount
FROM {{SalesOrderLine}} l
JOIN {{Product}} p ON p.{{Product.productId}} = l.{{SalesOrderLine.productId}}
WHERE l.{{SalesOrderLine.orderId}} = :orderId
