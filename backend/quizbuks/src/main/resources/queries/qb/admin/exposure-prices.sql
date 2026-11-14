/*---
id: qb.admin.exposure-prices
description: "Every operator's exposure price (F-M8): the price per exposure in yen and who changed it and why; an operator without one charges nothing"
entities: [QbOperator, QbExposurePrice]
datasets:
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbExposurePrice: urn:jabiz:dataset:default:QbExposurePrice
results:
  operatorId:   { from: QbOperator.operatorId }
  operatorName: { from: QbOperator.name }
  priceId:      { from: QbExposurePrice.priceId }
  unit:         { from: QbExposurePrice.unit }
  reason:       { from: QbExposurePrice.reason }
  updatedAt:    { from: QbExposurePrice.updatedAt }
  updatedBy:    { from: QbExposurePrice.updatedBy }
list:
  sorts: [operatorName]
  defaultSort: { field: operatorName, asc: true }
  key: [operatorId]
permissions: [qb.fee.manage]
---*/
-- The headquarters' range is the parameters qb.exposure.unit.min and .max (the page reads them as parameters).
SELECT o.{{QbOperator.operatorId}} AS operatorId, o.{{QbOperator.name}} AS operatorName,
       p.{{QbExposurePrice.priceId}} AS priceId, p.{{QbExposurePrice.unit}} AS unit, p.{{QbExposurePrice.reason}} AS reason,
       p.{{QbExposurePrice.updatedAt}} AS updatedAt, p.{{QbExposurePrice.updatedBy}} AS updatedBy
FROM {{QbOperator}} o
LEFT JOIN {{QbExposurePrice}} p ON p.{{QbExposurePrice.operatorId}} = CAST(o.{{QbOperator.operatorId}} AS text)
