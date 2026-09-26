/*---
id: broken.wrong_field
entities: [WaybillTracking]
results:
  waybillSn: { from: WaybillTracking.waybillId }
permissions: [it.read]
---*/
SELECT w.{{WaybillTracking.waybillId}} AS waybillSn
FROM {{WaybillTracking}} w
WHERE w.{{WaybillTracking.freigtCharge}} > 0
