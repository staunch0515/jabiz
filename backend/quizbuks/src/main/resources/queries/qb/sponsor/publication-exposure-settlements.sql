/*---
id: qb.sponsor.publication-exposure-settlements
description: "A Sponsor's publication's exposure fee settlements (F-M8), one a month and the final one: the exposures it counted, the price per exposure, the fee, its tax and the charge"
entities: [QbExposureSettlement]
datasets:
  QbExposureSettlement: urn:jabiz:dataset:sponsor:QbExposureSettlement
params:
  publicationId: { like: QbExposureSettlement.publicationId, required: true, description: The publication }
results:
  settlementId: { from: QbExposureSettlement.settlementId }
  period:       { from: QbExposureSettlement.period }
  fromCount:    { from: QbExposureSettlement.fromCount }
  toCount:      { from: QbExposureSettlement.toCount }
  counted:      { from: QbExposureSettlement.counted }
  unit:         { from: QbExposureSettlement.unit }
  amount:       { from: QbExposureSettlement.amount }
  tax:          { from: QbExposureSettlement.tax }
  total:        { from: QbExposureSettlement.total }
  final:        { from: QbExposureSettlement.final }
  chargeId:     { from: QbExposureSettlement.chargeId }
  settledAt:    { from: QbExposureSettlement.settledAt }
list:
  sorts: [period, settledAt]
  defaultSort: { field: period, asc: false }
  key: [settlementId]
permissions: [qb.sponsor.finance.read]
---*/
-- The dataset is the caller's own: another Sponsor's publication has no rows.
SELECT d.{{QbExposureSettlement.settlementId}} AS settlementId,
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
WHERE d.{{QbExposureSettlement.publicationId}} = :publicationId
