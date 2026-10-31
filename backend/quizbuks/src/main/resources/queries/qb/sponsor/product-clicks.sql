/*---
id: qb.sponsor.product-clicks
description: >-
  The clicks on each of the sponsor's products' shop links in a period (Q60c), the products without any included,
  most clicked first: visitors counted once a day per product, approximate (platform decision D65)
entities: [QbSponsorProduct, PublicCounterDay]
datasets:
  QbSponsorProduct: urn:jabiz:dataset:me:QbSponsorProduct
  PublicCounterDay: urn:jabiz:dataset:me:PublicCounterDay
params:
  from: { kind: { type: date }, required: true, description: The first day }
  to:   { kind: { type: date }, required: true, description: The last day }
results:
  productId: { from: QbSponsorProduct.productId }
  name:      { from: QbSponsorProduct.name }
  status:    { from: QbSponsorProduct.status }
  seq:       { from: QbSponsorProduct.seq }
  clicks:    { kind: { type: numeric, precision: 18, scale: 0 } }
list:
  sorts: [clicks, name, seq]
  defaultSort: { field: clicks, asc: false }
  key: [productId]
permissions: [qb.sponsor.me]
---*/
-- The caller's products (their dataset) with the caller's counts of their clicks (their dataset of counts); a deleted
-- product's counts are not shown.
SELECT p.{{QbSponsorProduct.productId}} AS productId, p.{{QbSponsorProduct.name}} AS name,
    p.{{QbSponsorProduct.status}} AS status, p.{{QbSponsorProduct.seq}} AS seq,
    coalesce(sum(c.{{PublicCounterDay.hits}}), 0) AS clicks
FROM {{QbSponsorProduct}} p
LEFT JOIN {{PublicCounterDay}} c
    ON c.{{PublicCounterDay.counterName}} = 'qb.product-click'
    AND c.{{PublicCounterDay.counterKey}} = p.{{QbSponsorProduct.productId}}
    AND c.{{PublicCounterDay.day}} BETWEEN CAST(:from AS date) AND CAST(:to AS date)
GROUP BY p.{{QbSponsorProduct.productId}}, p.{{QbSponsorProduct.name}}, p.{{QbSponsorProduct.status}},
    p.{{QbSponsorProduct.seq}}
