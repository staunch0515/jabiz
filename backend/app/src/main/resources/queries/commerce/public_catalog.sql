/*---
id: commerce.public.catalog
description: Products on sale, as the public shop window shows them (docs/design/15-public-access.md)
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [Product]
datasets: { Product: "urn:jabiz:dataset:public:Product" }
params:
  q:        { like: Product.productName, description: Part of the product name }
  maxPrice: { like: Product.unitPrice, description: Only products up to this price }
results:
  productId:   { from: Product.productId }
  sku:         { from: Product.sku }
  productName: { from: Product.productName }
  unitPrice:   { from: Product.unitPrice }
  imageFileId: { from: Product.imageFileId }
list:
  filters: [sku, unitPrice]
  sorts:   [sku, productName, unitPrice]
  defaultSort: { field: sku, asc: true }
  key: [productId]
---*/
SELECT
    p.{{Product.productId}}   AS productId,
    p.{{Product.sku}}         AS sku,
    p.{{Product.productName}} AS productName,
    p.{{Product.unitPrice}}   AS unitPrice,
    p.{{Product.imageFileId}} AS imageFileId
FROM {{Product}} p
WHERE (CAST(:q AS text) IS NULL OR p.{{Product.productName}} ILIKE '%' || CAST(:q AS text) || '%')
  AND (CAST(:maxPrice AS numeric) IS NULL OR p.{{Product.unitPrice}} <= :maxPrice)
