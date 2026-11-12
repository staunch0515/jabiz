/*---
id: qb.admin.distribution-prices
description: "Every operator's distribution price (F-M6): the rate (yen per start for each Kudos of the highest tier), the floor and ceiling of the price per start, the least deposit, and who changed it last and why; an operator without a row charges no distribution fee"
entities: [QbOperator, QbDistributionPrice]
datasets:
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbDistributionPrice: urn:jabiz:dataset:default:QbDistributionPrice
results:
  operatorId:     { from: QbOperator.operatorId }
  operatorName:   { from: QbOperator.name }
  priceId:        { from: QbDistributionPrice.priceId }
  rate:           { from: QbDistributionPrice.rate }
  unitFloor:      { from: QbDistributionPrice.unitFloor }
  unitCeiling:    { from: QbDistributionPrice.unitCeiling }
  minimumDeposit: { from: QbDistributionPrice.minimumDeposit }
  reason:         { from: QbDistributionPrice.reason }
  updatedAt:      { from: QbDistributionPrice.updatedAt }
  updatedBy:      { from: QbDistributionPrice.updatedBy }
list:
  sorts: [operatorName]
  defaultSort: { field: operatorName, asc: true }
  key: [operatorId]
permissions: [qb.fee.billing]
---*/
-- The headquarters' range is the parameters qb.distribution.* (the page reads them as parameters).
SELECT o.{{QbOperator.operatorId}} AS operatorId, o.{{QbOperator.name}} AS operatorName,
       p.{{QbDistributionPrice.priceId}} AS priceId, p.{{QbDistributionPrice.rate}} AS rate,
       p.{{QbDistributionPrice.unitFloor}} AS unitFloor, p.{{QbDistributionPrice.unitCeiling}} AS unitCeiling,
       p.{{QbDistributionPrice.minimumDeposit}} AS minimumDeposit, p.{{QbDistributionPrice.reason}} AS reason,
       p.{{QbDistributionPrice.updatedAt}} AS updatedAt, p.{{QbDistributionPrice.updatedBy}} AS updatedBy
FROM {{QbOperator}} o
LEFT JOIN {{QbDistributionPrice}} p ON p.{{QbDistributionPrice.operatorId}} = CAST(o.{{QbOperator.operatorId}} AS text)
