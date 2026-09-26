/*---
id: commerce.order_summary
description: Sales orders with their warehouse, number of lines and quantity
entities: [SalesOrder, SalesOrderLine, Warehouse]
params:
  customerCode:  { like: SalesOrder.customerCode, description: Only this customer when given }
  orderedFrom:   { like: SalesOrder.orderedTime, description: Only orders placed at or after this time }
results:
  orderNo:       { from: SalesOrder.orderNo }
  customerCode:  { from: SalesOrder.customerCode }
  warehouseCode: { from: Warehouse.warehouseCode }
  status:        { from: SalesOrder.status }
  orderedTime:   { from: SalesOrder.orderedTime }
  totalAmount:   { from: SalesOrder.totalAmount }
  lineCount:     { kind: { type: numeric, precision: 9, scale: 0 } }
  totalQuantity: { kind: { type: numeric, precision: 12, scale: 0 } }
list:
  filters: [orderNo, customerCode, warehouseCode, status, orderedTime, totalAmount]
  sorts:   [orderNo, orderedTime, totalAmount]
  defaultSort: { field: orderedTime, asc: false }
  key: [orderNo]
permissions: [commerce.order.read]
---*/
SELECT
    o.{{SalesOrder.orderNo}}      AS orderNo,
    o.{{SalesOrder.customerCode}} AS customerCode,
    w.{{Warehouse.warehouseCode}} AS warehouseCode,
    o.{{SalesOrder.status}}       AS status,
    o.{{SalesOrder.orderedTime}}  AS orderedTime,
    o.{{SalesOrder.totalAmount}}  AS totalAmount,
    count(l.{{SalesOrderLine.orderLineId}})                     AS lineCount,
    coalesce(sum(l.{{SalesOrderLine.quantity}}), 0)             AS totalQuantity
FROM {{SalesOrder}} o
JOIN {{Warehouse}} w           ON w.{{Warehouse.warehouseId}} = o.{{SalesOrder.warehouseId}}
LEFT JOIN {{SalesOrderLine}} l ON l.{{SalesOrderLine.orderId}} = o.{{SalesOrder.orderId}}
WHERE (CAST(:customerCode AS text) IS NULL OR o.{{SalesOrder.customerCode}} = :customerCode)
  AND (CAST(:orderedFrom AS timestamptz) IS NULL OR o.{{SalesOrder.orderedTime}} >= :orderedFrom)
GROUP BY o.{{SalesOrder.orderNo}}, o.{{SalesOrder.customerCode}}, w.{{Warehouse.warehouseCode}},
         o.{{SalesOrder.status}}, o.{{SalesOrder.orderedTime}}, o.{{SalesOrder.totalAmount}}
