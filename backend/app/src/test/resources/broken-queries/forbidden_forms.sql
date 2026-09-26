/*---
id: broken.forbidden_forms
entities: [WaybillTracking]
params:
  statuses: { like: WaybillTracking.status, list: true }
results:
  waybillSn: { from: WaybillTracking.waybillId }
---*/
SELECT w.{{WaybillTracking.waybillId}} AS waybillSn
FROM {{WaybillTracking}} w
WHERE w.{{WaybillTracking.status}} IN (:statuses)
LIMIT 10
