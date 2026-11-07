/*---
id: it.verify_rows
description: A restore check of the verification tests (rows), passing when it returns no rows
entities: [Warehouse]
results:
  warehouseCode: { from: Warehouse.warehouseCode }
list:
  key: [warehouseCode]
permissions: [it.read]
---*/
SELECT w.{{Warehouse.warehouseCode}} AS warehouseCode
FROM {{Warehouse}} w
WHERE w.{{Warehouse.active}}
