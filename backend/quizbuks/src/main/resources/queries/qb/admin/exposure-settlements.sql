/*---
id: qb.admin.exposure-settlements
description: "Every exposure fee settlement (F-M8): the publication, its Sponsor and operator, the month, the exposures counted since the last settlement and in all, the price per exposure, the fee, its tax and the charge"
entities: [QbExposureSettlement]
datasets:
  QbExposureSettlement: urn:jabiz:dataset:default:QbExposureSettlement
params:
  period:        { like: QbExposureSettlement.period, description: Only this month }
  sponsorId:     { like: QbExposureSettlement.sponsorId, description: Only this Sponsor }
  operatorId:    { like: QbExposureSettlement.operatorId, description: Only this operator }
  publicationId: { like: QbExposureSettlement.publicationId, description: Only this publication }
results:
  settlementId:  { from: QbExposureSettlement.settlementId }
  publicationId: { from: QbExposureSettlement.publicationId }
  sponsorId:     { from: QbExposureSettlement.sponsorId }
  operatorId:    { from: QbExposureSettlement.operatorId }
  period:        { from: QbExposureSettlement.period }
  fromCount:     { from: QbExposureSettlement.fromCount }
  toCount:       { from: QbExposureSettlement.toCount }
  counted:       { from: QbExposureSettlement.counted }
  unit:          { from: QbExposureSettlement.unit }
  amount:        { from: QbExposureSettlement.amount }
  tax:           { from: QbExposureSettlement.tax }
  total:         { from: QbExposureSettlement.total }
  final:         { from: QbExposureSettlement.final }
  chargeId:      { from: QbExposureSettlement.chargeId }
  settledAt:     { from: QbExposureSettlement.settledAt }
list:
  sorts: [settledAt, period]
  defaultSort: { field: settledAt, asc: false }
  key: [settlementId]
permissions: [qb.admin.finance.read]
---*/
SELECT d.{{QbExposureSettlement.settlementId}} AS settlementId,
       d.{{QbExposureSettlement.publicationId}} AS publicationId,
       d.{{QbExposureSettlement.sponsorId}} AS sponsorId,
       d.{{QbExposureSettlement.operatorId}} AS operatorId,
       d.{{QbExposureSettlement.period}} AS period,
       d.{{QbExposureSettlement.fromCount}} AS fromCount,
       d.{{QbExposureSettlement.toCount}} AS toCount,
       d.{{QbExposureSettlement.counted}} AS counted,
       d.{{QbExposureSettlement.unit}} AS unit,
       d.{{QbExposureSettlement.amount}} AS amount,
       d.{{QbExposureSettlement.tax}} AS tax,
       d.{{QbExposureSettlement.total}} AS total,
       d.{{QbExposureSettlement.final}} AS final,
       d.{{QbExposureSettlement.chargeId}} AS chargeId,
       d.{{QbExposureSettlement.settledAt}} AS settledAt
FROM {{QbExposureSettlement}} d
WHERE (CAST(:period AS text) IS NULL OR d.{{QbExposureSettlement.period}} = :period)
  AND (CAST(:sponsorId AS text) IS NULL OR d.{{QbExposureSettlement.sponsorId}} = :sponsorId)
  AND (CAST(:operatorId AS text) IS NULL OR d.{{QbExposureSettlement.operatorId}} = :operatorId)
  AND (CAST(:publicationId AS text) IS NULL OR d.{{QbExposureSettlement.publicationId}} = :publicationId)
