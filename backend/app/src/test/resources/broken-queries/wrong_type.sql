/*---
id: broken.wrong_type
entities: [WaybillTracking]
results:
  waybillSn:   { from: WaybillTracking.waybillId }
  shippedTime: { kind: { type: temporal, role: EVENT_TIME } }
permissions: [it.read]
---*/
SELECT w.{{WaybillTracking.waybillId}}     AS waybillSn,
       w.{{WaybillTracking.freightCharge}} AS shippedTime
FROM {{WaybillTracking}} w
