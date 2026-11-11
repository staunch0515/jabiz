/*---
id: qb.admin.audience-prices
description: "Every operator's audience price list (Q47c), with the least fee, the daily prices by rule and who changed it last and why (F-M5: headquarters' own list is gone, the requester 2026-11-09; its rows stay unread until a later clean-up)"
entities: [QbAudiencePriceList, QbAudiencePrice, QbOperator]
datasets:
  QbAudiencePriceList: urn:jabiz:dataset:default:QbAudiencePriceList
  QbAudiencePrice: urn:jabiz:dataset:default:QbAudiencePrice
  QbOperator: urn:jabiz:dataset:default:QbOperator
params:
  owner: { like: QbAudiencePriceList.owner, description: Only this owner's list }
results:
  priceListId:  { from: QbAudiencePriceList.priceListId }
  owner:        { from: QbAudiencePriceList.owner }
  operatorName: { from: QbOperator.name }
  minimumFee:   { from: QbAudiencePriceList.minimumFee }
  prices:       { kind: { type: text, maxLength: 4000 } }
  reason:       { from: QbAudiencePriceList.reason }
  updatedAt:    { from: QbAudiencePriceList.updatedAt }
  updatedBy:    { from: QbAudiencePriceList.updatedBy }
list:
  sorts: [owner, updatedAt]
  defaultSort: { field: owner, asc: true }
  key: [priceListId]
permissions: [qb.audience.prices.write]
---*/
-- prices: a JSON object of the daily price by rule key (rules without a row cost 0).
SELECT
    l.{{QbAudiencePriceList.priceListId}} AS priceListId,
    l.{{QbAudiencePriceList.owner}}       AS owner,
    o.{{QbOperator.name}}                 AS operatorName,
    l.{{QbAudiencePriceList.minimumFee}}  AS minimumFee,
    (SELECT coalesce(json_object_agg(p.{{QbAudiencePrice.ruleKey}}, p.{{QbAudiencePrice.dailyPrice}}), '{}'::json)::text
     FROM {{QbAudiencePrice}} p
     WHERE p.{{QbAudiencePrice.priceListId}} = l.{{QbAudiencePriceList.priceListId}}) AS prices,
    l.{{QbAudiencePriceList.reason}}      AS reason,
    l.{{QbAudiencePriceList.updatedAt}}   AS updatedAt,
    l.{{QbAudiencePriceList.updatedBy}}   AS updatedBy
FROM {{QbAudiencePriceList}} l
JOIN {{QbOperator}} o ON CAST(o.{{QbOperator.operatorId}} AS text) = l.{{QbAudiencePriceList.owner}}
WHERE (CAST(:owner AS text) IS NULL OR l.{{QbAudiencePriceList.owner}} = CAST(:owner AS text))
