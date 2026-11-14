/*---
id: qb.admin.distribution-settlements
description: "Every distribution fee settlement (F-M6): the publication, its Sponsor and operator (with its name), the month, the starts, the price per start (and whether it is the floor), the fee, its tax and the charge"
entities: [QbDistributionSettlement, QbOperator]
datasets:
  QbDistributionSettlement: urn:jabiz:dataset:default:QbDistributionSettlement
  QbOperator: urn:jabiz:dataset:default:QbOperator
params:
  period:        { like: QbDistributionSettlement.period, description: Only this month }
  sponsorId:     { like: QbDistributionSettlement.sponsorId, description: Only this Sponsor }
  operatorId:    { like: QbDistributionSettlement.operatorId, description: Only this operator }
  publicationId: { like: QbDistributionSettlement.publicationId, description: Only this publication }
  atFloor:       { like: QbDistributionSettlement.atFloor, description: Only those charged the floor (true) or not (false) }
results:
  settlementId:  { from: QbDistributionSettlement.settlementId }
  publicationId: { from: QbDistributionSettlement.publicationId }
  sponsorId:     { from: QbDistributionSettlement.sponsorId }
  operatorId:    { from: QbDistributionSettlement.operatorId }
  operatorName:  { from: QbOperator.name }
  period:        { from: QbDistributionSettlement.period }
  starts:        { from: QbDistributionSettlement.starts }
  toStarts:      { from: QbDistributionSettlement.toStarts }
  unit:          { from: QbDistributionSettlement.unit }
  atFloor:       { from: QbDistributionSettlement.atFloor }
  amount:        { from: QbDistributionSettlement.amount }
  tax:           { from: QbDistributionSettlement.tax }
  total:         { from: QbDistributionSettlement.total }
  final:         { from: QbDistributionSettlement.final }
  chargeId:      { from: QbDistributionSettlement.chargeId }
  settledAt:     { from: QbDistributionSettlement.settledAt }
list:
  sorts: [settledAt, period]
  defaultSort: { field: settledAt, asc: false }
  key: [settlementId]
permissions: [qb.admin.finance.read]
---*/
SELECT d.{{QbDistributionSettlement.settlementId}} AS settlementId,
       d.{{QbDistributionSettlement.publicationId}} AS publicationId,
       d.{{QbDistributionSettlement.sponsorId}} AS sponsorId, d.{{QbDistributionSettlement.operatorId}} AS operatorId,
       o.{{QbOperator.name}} AS operatorName,
       d.{{QbDistributionSettlement.period}} AS period, d.{{QbDistributionSettlement.starts}} AS starts,
       d.{{QbDistributionSettlement.toStarts}} AS toStarts, d.{{QbDistributionSettlement.unit}} AS unit,
       d.{{QbDistributionSettlement.atFloor}} AS atFloor, d.{{QbDistributionSettlement.amount}} AS amount,
       d.{{QbDistributionSettlement.tax}} AS tax, d.{{QbDistributionSettlement.total}} AS total,
       d.{{QbDistributionSettlement.final}} AS final, d.{{QbDistributionSettlement.chargeId}} AS chargeId,
       d.{{QbDistributionSettlement.settledAt}} AS settledAt
FROM {{QbDistributionSettlement}} d
LEFT JOIN {{QbOperator}} o ON o.{{QbOperator.operatorId}} = d.{{QbDistributionSettlement.operatorId}}
WHERE (CAST(:period AS text) IS NULL OR d.{{QbDistributionSettlement.period}} = :period)
  AND (CAST(:sponsorId AS text) IS NULL OR d.{{QbDistributionSettlement.sponsorId}} = :sponsorId)
  AND (CAST(:operatorId AS text) IS NULL OR d.{{QbDistributionSettlement.operatorId}} = :operatorId)
  AND (CAST(:publicationId AS text) IS NULL OR d.{{QbDistributionSettlement.publicationId}} = :publicationId)
  AND (CAST(:atFloor AS boolean) IS NULL OR d.{{QbDistributionSettlement.atFloor}} = :atFloor)
