/*---
id: logistics.freight_charges
description: Freight charges of one month (Japan time) with their fuel surcharge
entities: [FreightCharge]
params:
  month: { like: FreightCharge.chargeMonth, required: true }
results:
  waybillId:       { from: FreightCharge.waybillId }
  shippedTime:     { from: FreightCharge.shippedTime }
  surchargeRate:   { from: FreightCharge.surchargeRate }
  surchargeAmount: { from: FreightCharge.surchargeAmount }
  totalAmount:     { from: FreightCharge.totalAmount }
  settled:         { from: FreightCharge.settled }
list:
  filters: [settled]
  sorts:   [waybillId, shippedTime]
  defaultSort: { field: waybillId, asc: true }
  key: [waybillId]
permissions: [logistics.freight.read]
---*/
SELECT
    c.{{FreightCharge.waybillId}}       AS waybillId,
    c.{{FreightCharge.shippedTime}}     AS shippedTime,
    c.{{FreightCharge.surchargeRate}}   AS surchargeRate,
    c.{{FreightCharge.surchargeAmount}} AS surchargeAmount,
    c.{{FreightCharge.totalAmount}}     AS totalAmount,
    c.{{FreightCharge.settled}}         AS settled
FROM {{FreightCharge}} c
WHERE c.{{FreightCharge.chargeMonth}} = :month
