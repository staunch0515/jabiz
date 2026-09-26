/*---
id: broken.bad_header
entities: [WaybillTracking]
results:
  waybillSn: { from: WaybillTracking.waybillId, colour: red }
permissions: [it.read]
---*/
SELECT w.{{WaybillTracking.waybillId}} AS waybillSn FROM {{WaybillTracking}} w
