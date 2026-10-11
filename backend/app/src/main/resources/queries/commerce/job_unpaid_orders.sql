/*---
id: commerce.job.unpaid-orders
description: Placed orders still unpaid that were placed before the cutoff, oldest first (the items of the job commerce.unpaid-order-expire)
entities: [SalesOrder]
params:
  cutoff: { like: SalesOrder.orderedTime, required: true, description: Orders placed before this time }
results:
  orderId:     { from: SalesOrder.orderId }
  orderedTime: { from: SalesOrder.orderedTime }
list:
  sorts: [orderedTime]
  defaultSort: { field: orderedTime, asc: true }
  key: [orderId]
permissions: [commerce.order.cancel]
---*/
SELECT o.{{SalesOrder.orderId}} AS orderId, o.{{SalesOrder.orderedTime}} AS orderedTime
FROM {{SalesOrder}} o
WHERE o.{{SalesOrder.status}} = 'PLACED'
  AND o.{{SalesOrder.paidTime}} IS NULL
  AND o.{{SalesOrder.orderedTime}} < :cutoff
