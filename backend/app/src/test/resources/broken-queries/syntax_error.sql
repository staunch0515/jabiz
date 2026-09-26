/*---
id: broken.syntax_error
entities: [WaybillTracking]
params:
  minFreight: { like: WaybillTracking.freightCharge }
results:
  waybillSn: { from: WaybillTracking.waybillId }
permissions: [it.read]
---*/
SELECT w.{{WaybillTracking.waybillId}} AS waybillSn
FROM {{WaybillTracking}} w
WHERE w.{{WaybillTracking.freightCharge}} >= :minFreight
  AND w.no_such_column = 1
