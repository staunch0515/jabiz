/*---
id: qb.public.sponsor-page-products
description: >-
  The products a sponsor's published page shows (Q60b), in the sponsor's order: name, description, image, price with
  its currency and the shop's link; none when the page is not published
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [QbSponsorProduct, QbSponsorPage]
datasets:
  QbSponsorProduct: urn:jabiz:dataset:public:QbSponsorProduct
  QbSponsorPage: urn:jabiz:dataset:public:QbSponsorPage
params:
  handle: { like: QbSponsorPage.handle, required: true, description: The page's handle (lowercase) }
results:
  productId:   { from: QbSponsorProduct.productId }
  name:        { from: QbSponsorProduct.name }
  description: { from: QbSponsorProduct.description }
  image:       { from: QbSponsorProduct.image }
  price:       { from: QbSponsorProduct.price }
  currency:    { from: QbSponsorProduct.currency }
  shopUrl:     { from: QbSponsorProduct.shopUrl }
  seq:         { from: QbSponsorProduct.seq }
list:
  key: [productId]
  sorts: [seq]
  defaultSort: { field: seq, asc: true }
---*/
-- Listed products only (their dataset's fixed scope), of a published page only (the join).
SELECT
    pr.{{QbSponsorProduct.productId}}   AS productId,
    pr.{{QbSponsorProduct.name}}        AS name,
    pr.{{QbSponsorProduct.description}} AS description,
    pr.{{QbSponsorProduct.image}}       AS image,
    pr.{{QbSponsorProduct.price}}       AS price,
    pr.{{QbSponsorProduct.currency}}    AS currency,
    pr.{{QbSponsorProduct.shopUrl}}     AS shopUrl,
    pr.{{QbSponsorProduct.seq}}         AS seq
FROM {{QbSponsorProduct}} pr
JOIN {{QbSponsorPage}} p ON p.{{QbSponsorPage.userId}} = pr.{{QbSponsorProduct.userId}}
WHERE p.{{QbSponsorPage.handle}} = :handle
