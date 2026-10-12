/*---
id: commerce.portal_orders
description: The caller's own orders, for the customer portal (docs/guide/app-spa.md)
entities: [SalesOrder, SalesOrderLine, Customer]
datasets: { Customer: "urn:jabiz:dataset:commerce:PortalCustomer" }
results:
  orderNo:     { from: SalesOrder.orderNo }
  status:      { from: SalesOrder.status }
  orderedTime: { from: SalesOrder.orderedTime }
  totalAmount: { from: SalesOrder.totalAmount }
  lineCount:   { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts:   [orderNo, orderedTime, totalAmount]
  defaultSort: { field: orderedTime, asc: false }
  key: [orderNo]
permissions: [commerce.portal.order]
---*/
SELECT
    o.{{SalesOrder.orderNo}}     AS orderNo,
    o.{{SalesOrder.status}}      AS status,
    o.{{SalesOrder.orderedTime}} AS orderedTime,
    o.{{SalesOrder.totalAmount}} AS totalAmount,
    count(l.{{SalesOrderLine.orderLineId}}) AS lineCount
FROM {{SalesOrder}} o
JOIN {{Customer}} c            ON c.{{Customer.customerCode}} = o.{{SalesOrder.customerCode}}
LEFT JOIN {{SalesOrderLine}} l ON l.{{SalesOrderLine.orderId}} = o.{{SalesOrder.orderId}}
GROUP BY o.{{SalesOrder.orderNo}}, o.{{SalesOrder.status}}, o.{{SalesOrder.orderedTime}}, o.{{SalesOrder.totalAmount}}
