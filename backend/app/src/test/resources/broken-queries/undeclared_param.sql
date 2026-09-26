/*---
id: broken.undeclared_param
entities: [WaybillTracking]
params:
  minFreight: { like: WaybillTracking.freightCharge, required: true }
results:
  waybillSn: { from: WaybillTracking.waybillId }
permissions: [it.read]
---*/
SELECT w.{{WaybillTracking.waybillId}} AS waybillSn
FROM {{WaybillTracking}} w
WHERE w.{{WaybillTracking.freightCharge}} >= :minFreight
  AND w.{{WaybillTracking.freightCharge}} <= :maxFreight
