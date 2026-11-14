/*---
id: qb.operator.exposure-price
description: "The caller operator's exposure price (F-M8) and the headquarters' range of it: the price per exposure in yen; none: nothing is charged"
entities: [QbOperatorMember, QbExposurePrice, SysParam]
datasets:
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbExposurePrice: urn:jabiz:dataset:default:QbExposurePrice
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  operatorId: { kind: { type: text, maxLength: 36 } }
  lead:       { kind: { type: bool } }
  priceId:    { from: QbExposurePrice.priceId }
  unit:       { from: QbExposurePrice.unit }
  reason:     { from: QbExposurePrice.reason }
  updatedAt:  { from: QbExposurePrice.updatedAt }
  updatedBy:  { from: QbExposurePrice.updatedBy }
  range:      { kind: { type: text, maxLength: 2000 } }
permissions: [qb.operator.use]
---*/
-- Only through the caller's current membership (the view holds the caller's own), as the other operator templates.
-- range: a JSON object of the headquarters' bounds by parameter key (a bound not set is missing).
SELECT CAST(me.{{QbOperatorMember.operatorId}} AS text) AS operatorId, me.{{QbOperatorMember.lead}} AS lead,
       p.{{QbExposurePrice.priceId}} AS priceId, p.{{QbExposurePrice.unit}} AS unit, p.{{QbExposurePrice.reason}} AS reason,
       p.{{QbExposurePrice.updatedAt}} AS updatedAt, p.{{QbExposurePrice.updatedBy}} AS updatedBy,
       (SELECT coalesce(json_object_agg(s.{{SysParam.paramKey}}, s.{{SysParam.value}}), '{}'::json)::text
        FROM {{SysParam}} s
        WHERE s.{{SysParam.paramKey}} IN ('qb.exposure.unit.min', 'qb.exposure.unit.max')) AS range
FROM {{QbOperatorMember}} me
LEFT JOIN {{QbExposurePrice}} p
    ON p.{{QbExposurePrice.operatorId}} = CAST(me.{{QbOperatorMember.operatorId}} AS text)
WHERE me.{{QbOperatorMember.removedAt}} IS NULL
