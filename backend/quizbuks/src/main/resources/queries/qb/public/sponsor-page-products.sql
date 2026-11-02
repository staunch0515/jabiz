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
  languages: { kind: { type: text, maxLength: 20 }, description: "Q73c: as the page's: the language to show the products in" }
results:
  productId:   { from: QbSponsorProduct.productId }
  name:        { from: QbSponsorProduct.name }
  description: { from: QbSponsorProduct.description }
  image:       { from: QbSponsorProduct.image }
  price:       { from: QbSponsorProduct.price }
  currency:    { from: QbSponsorProduct.currency }
  shopUrl:     { from: QbSponsorProduct.shopUrl }
  seq:         { from: QbSponsorProduct.seq }
  textLanguage: { from: QbSponsorPage.language }
list:
  key: [productId]
  sorts: [seq]
  defaultSort: { field: seq, asc: true }
---*/
-- Listed products only (their dataset's fixed scope), of a published page only (the join).
SELECT
    pr.{{QbSponsorProduct.productId}}   AS productId,
    -- Q73c: in the page's language shown when the product is translated into it, else as written (textLanguage says
    -- which, so that the page can say so); a product need not be translated.
    coalesce(CAST(pr.{{QbSponsorProduct.translations}} AS jsonb) -> dl.lang -> 'name' ->> 'text',
        pr.{{QbSponsorProduct.name}})   AS name,
    CASE WHEN CAST(pr.{{QbSponsorProduct.translations}} AS jsonb) -> dl.lang -> 'name' ->> 'text' IS NOT NULL
        THEN coalesce(CAST(pr.{{QbSponsorProduct.translations}} AS jsonb) -> dl.lang -> 'description' ->> 'text',
            pr.{{QbSponsorProduct.description}})
        ELSE pr.{{QbSponsorProduct.description}} END AS description,
    pr.{{QbSponsorProduct.image}}       AS image,
    pr.{{QbSponsorProduct.price}}       AS price,
    pr.{{QbSponsorProduct.currency}}    AS currency,
    pr.{{QbSponsorProduct.shopUrl}}     AS shopUrl,
    pr.{{QbSponsorProduct.seq}}         AS seq,
    CASE WHEN CAST(pr.{{QbSponsorProduct.translations}} AS jsonb) -> dl.lang -> 'name' ->> 'text' IS NOT NULL
        THEN dl.lang ELSE p.{{QbSponsorPage.language}} END AS textLanguage
FROM {{QbSponsorProduct}} pr
JOIN {{QbSponsorPage}} p ON p.{{QbSponsorPage.userId}} = pr.{{QbSponsorProduct.userId}}
CROSS JOIN LATERAL (
    SELECT coalesce((SELECT u.l FROM unnest(string_to_array(CAST(:languages AS text), ',')) WITH ORDINALITY AS u(l, i)
                     WHERE u.l = ANY(string_to_array(coalesce(p.{{QbSponsorPage.languages}},
                         p.{{QbSponsorPage.language}}), ','))
                     ORDER BY u.i LIMIT 1),
        split_part(coalesce(p.{{QbSponsorPage.languages}}, p.{{QbSponsorPage.language}}), ',', 1)) AS lang) dl
WHERE p.{{QbSponsorPage.handle}} = :handle
