/*---
id: qb.operator.distribution-price
description: "The caller operator's distribution price (F-M6) and the headquarters' range of it: the rate (yen per start for each Kudos of the highest tier), the floor and ceiling of the price per start, the least deposit; no price before one is saved (its Sponsors pay no distribution fee)"
entities: [QbOperatorMember, QbDistributionPrice, SysParam]
datasets:
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbDistributionPrice: urn:jabiz:dataset:default:QbDistributionPrice
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  operatorId:     { kind: { type: text, maxLength: 36 } }
  lead:           { kind: { type: bool } }
  priceId:        { from: QbDistributionPrice.priceId }
  rate:           { from: QbDistributionPrice.rate }
  unitFloor:      { from: QbDistributionPrice.unitFloor }
  unitCeiling:    { from: QbDistributionPrice.unitCeiling }
  minimumDeposit: { from: QbDistributionPrice.minimumDeposit }
  reason:         { from: QbDistributionPrice.reason }
  updatedAt:      { from: QbDistributionPrice.updatedAt }
  updatedBy:      { from: QbDistributionPrice.updatedBy }
  range:          { kind: { type: text, maxLength: 2000 } }
permissions: [qb.operator.use]
---*/
-- Only through the caller's current membership (the view holds the caller's own), as the other operator templates.
-- range: a JSON object of the headquarters' bounds by parameter key (a bound not set is missing).
SELECT CAST(me.{{QbOperatorMember.operatorId}} AS text) AS operatorId, me.{{QbOperatorMember.lead}} AS lead,
       p.{{QbDistributionPrice.priceId}} AS priceId, p.{{QbDistributionPrice.rate}} AS rate,
       p.{{QbDistributionPrice.unitFloor}} AS unitFloor, p.{{QbDistributionPrice.unitCeiling}} AS unitCeiling,
       p.{{QbDistributionPrice.minimumDeposit}} AS minimumDeposit, p.{{QbDistributionPrice.reason}} AS reason,
       p.{{QbDistributionPrice.updatedAt}} AS updatedAt, p.{{QbDistributionPrice.updatedBy}} AS updatedBy,
       (SELECT coalesce(json_object_agg(s.{{SysParam.paramKey}}, s.{{SysParam.value}}), '{}'::json)::text
        FROM {{SysParam}} s
        WHERE s.{{SysParam.paramKey}} IN ('qb.distribution.rate.min', 'qb.distribution.rate.max',
              'qb.distribution.unit-floor.min', 'qb.distribution.unit-floor.max', 'qb.distribution.unit-ceiling.max',
              'qb.distribution.minimum-deposit.max')) AS range
FROM {{QbOperatorMember}} me
LEFT JOIN {{QbDistributionPrice}} p
    ON p.{{QbDistributionPrice.operatorId}} = CAST(me.{{QbOperatorMember.operatorId}} AS text)
WHERE me.{{QbOperatorMember.removedAt}} IS NULL
