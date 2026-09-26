/*---
id: logistics.tokyo_port_audit
description: Gross weight, declared duty and live spatial cell of waybills cleared at Tokyo port
entities: [WaybillTracking, CustomsDeclaration]
params:
  minFreight:      { like: WaybillTracking.freightCharge, required: true }
  # Narrower than WaybillTracking.status: only cleared waybills belong in this audit.
  allowedStatuses:
    kind: { type: code, dictUrn: "urn:ubos:dict:waybill_status", allowedValues: [CUSTOMS_CLEARED, DELIVERED] }
    list: true
    required: true
  shippedAfter:    { kind: { type: temporal, role: EVENT_TIME } }
results:
  waybillSn:       { from: WaybillTracking.waybillId }
  finalFreight:    { from: WaybillTracking.freightCharge }
  clearedWeightKg: { from: WaybillTracking.totalWeight }
  customsPortCell: { from: WaybillTracking.currentLocation }
  shippedTime:     { from: WaybillTracking.shippedTime }
  declarationNo:   { from: CustomsDeclaration.declarationId }
  dutyPaid:        { from: CustomsDeclaration.dutyAmount }
list:
  filters: [waybillSn, finalFreight, dutyPaid, shippedTime]
  sorts:   [shippedTime, finalFreight, waybillSn]
  defaultSort: { field: shippedTime, asc: false }
  key: [waybillSn, declarationNo]
permissions: [logistics.report.read]
timeoutMs: 3000
---*/
-- Tables and columns come from the metamodel, so the query follows physical renames and dataset scopes.
SELECT
    w.{{WaybillTracking.waybillId}}        AS waybillSn,
    w.{{WaybillTracking.freightCharge}}    AS finalFreight,
    w.{{WaybillTracking.totalWeight}}      AS clearedWeightKg,
    w.{{WaybillTracking.currentLocation}}  AS customsPortCell,
    w.{{WaybillTracking.shippedTime}}      AS shippedTime,
    c.{{CustomsDeclaration.declarationId}} AS declarationNo,
    c.{{CustomsDeclaration.dutyAmount}}    AS dutyPaid
FROM {{WaybillTracking}} w
INNER JOIN {{CustomsDeclaration}} c
    ON w.{{WaybillTracking.waybillId}} = c.{{CustomsDeclaration.waybillRef}}
WHERE w.{{WaybillTracking.freightCharge}} >= :minFreight
  AND w.{{WaybillTracking.status}} = ANY(:allowedStatuses)
  AND (CAST(:shippedAfter AS timestamptz) IS NULL
       OR w.{{WaybillTracking.shippedTime}} >= :shippedAfter)
