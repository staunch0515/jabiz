/*---
id: qb.operator.audience-prices
description: "The caller operator's audience price list (Q47c): the least fee and the daily prices by rule; none before its lead saved one (its sponsors are then priced by the fallback)"
entities: [QbAudiencePriceList, QbAudiencePrice, QbOperatorMember]
datasets:
  QbAudiencePriceList: urn:jabiz:dataset:default:QbAudiencePriceList
  QbAudiencePrice: urn:jabiz:dataset:default:QbAudiencePrice
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
results:
  priceListId: { from: QbAudiencePriceList.priceListId }
  minimumFee:  { from: QbAudiencePriceList.minimumFee }
  prices:      { kind: { type: text, maxLength: 4000 } }
  reason:      { from: QbAudiencePriceList.reason }
  updatedAt:   { from: QbAudiencePriceList.updatedAt }
  updatedBy:   { from: QbAudiencePriceList.updatedBy }
permissions: [qb.operator.use]
---*/
-- Only through the caller's current membership (the view holds the caller's own), as the other operator templates.
SELECT
    l.{{QbAudiencePriceList.priceListId}} AS priceListId,
    l.{{QbAudiencePriceList.minimumFee}}  AS minimumFee,
    (SELECT coalesce(json_object_agg(p.{{QbAudiencePrice.ruleKey}}, p.{{QbAudiencePrice.dailyPrice}}), '{}'::json)::text
     FROM {{QbAudiencePrice}} p
     WHERE p.{{QbAudiencePrice.priceListId}} = l.{{QbAudiencePriceList.priceListId}}) AS prices,
    l.{{QbAudiencePriceList.reason}}      AS reason,
    l.{{QbAudiencePriceList.updatedAt}}   AS updatedAt,
    l.{{QbAudiencePriceList.updatedBy}}   AS updatedBy
FROM {{QbAudiencePriceList}} l
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
  AND CAST(me.{{QbOperatorMember.operatorId}} AS text) = l.{{QbAudiencePriceList.owner}}
