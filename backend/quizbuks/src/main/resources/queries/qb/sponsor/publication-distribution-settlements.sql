/*---
id: qb.sponsor.publication-distribution-settlements
description: "A Sponsor's publication's distribution fee settlements (F-M6), one a month: the starts it counted, the price per start, the fee, its tax and the charge"
entities: [QbDistributionSettlement]
datasets:
  QbDistributionSettlement: urn:jabiz:dataset:sponsor:QbDistributionSettlement
params:
  publicationId: { like: QbDistributionSettlement.publicationId, required: true, description: The publication }
results:
  settlementId: { from: QbDistributionSettlement.settlementId }
  period:       { from: QbDistributionSettlement.period }
  starts:       { from: QbDistributionSettlement.starts }
  toStarts:     { from: QbDistributionSettlement.toStarts }
  unit:         { from: QbDistributionSettlement.unit }
  atFloor:      { from: QbDistributionSettlement.atFloor }
  amount:       { from: QbDistributionSettlement.amount }
  tax:          { from: QbDistributionSettlement.tax }
  total:        { from: QbDistributionSettlement.total }
  final:        { from: QbDistributionSettlement.final }
  chargeId:     { from: QbDistributionSettlement.chargeId }
  settledAt:    { from: QbDistributionSettlement.settledAt }
list:
  sorts: [period, settledAt]
  defaultSort: { field: period, asc: false }
  key: [settlementId]
permissions: [qb.sponsor.finance.read]
---*/
-- The dataset is the caller's own: another Sponsor's publication has no rows.
SELECT d.{{QbDistributionSettlement.settlementId}} AS settlementId, d.{{QbDistributionSettlement.period}} AS period,
       d.{{QbDistributionSettlement.starts}} AS starts, d.{{QbDistributionSettlement.toStarts}} AS toStarts,
       d.{{QbDistributionSettlement.unit}} AS unit, d.{{QbDistributionSettlement.atFloor}} AS atFloor,
       d.{{QbDistributionSettlement.amount}} AS amount, d.{{QbDistributionSettlement.tax}} AS tax,
       d.{{QbDistributionSettlement.total}} AS total, d.{{QbDistributionSettlement.final}} AS final,
       d.{{QbDistributionSettlement.chargeId}} AS chargeId, d.{{QbDistributionSettlement.settledAt}} AS settledAt
FROM {{QbDistributionSettlement}} d
WHERE d.{{QbDistributionSettlement.publicationId}} = :publicationId
