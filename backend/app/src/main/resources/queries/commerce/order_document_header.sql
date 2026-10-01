/*---
id: commerce.order_document_header
description: One sales order as its confirmation shows it (document commerce.order_confirmation, docs/design/22-documents.md)
entities: [SalesOrder, Warehouse]
params:
  orderId: { like: SalesOrder.orderId, required: true, description: The order }
results:
  orderNo:       { from: SalesOrder.orderNo }
  customerCode:  { from: SalesOrder.customerCode }
  orderedTime:   { from: SalesOrder.orderedTime }
  warehouseCode: { from: Warehouse.warehouseCode }
  warehouseName: { from: Warehouse.warehouseName }
  totalAmount:   { from: SalesOrder.totalAmount }
list:
  sorts: [orderNo]
  defaultSort: { field: orderNo, asc: true }
  key: [orderNo]
permissions: [commerce.order.read]
---*/
SELECT
    o.{{SalesOrder.orderNo}}       AS orderNo,
    o.{{SalesOrder.customerCode}}  AS customerCode,
    o.{{SalesOrder.orderedTime}}   AS orderedTime,
    w.{{Warehouse.warehouseCode}}  AS warehouseCode,
    w.{{Warehouse.warehouseName}}  AS warehouseName,
    o.{{SalesOrder.totalAmount}}   AS totalAmount
FROM {{SalesOrder}} o
JOIN {{Warehouse}} w ON w.{{Warehouse.warehouseId}} = o.{{SalesOrder.warehouseId}}
WHERE o.{{SalesOrder.orderId}} = :orderId
