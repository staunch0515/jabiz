/*---
id: qb.admin.sponsor-products
description: >-
  Sponsors' products (Q60b) with the sponsor's company and their page's link name, by status, sponsor or name: the list
  to take products down from and put them back; an operator's session sees its own sponsors' only
entities: [QbSponsorProduct, QbSponsor, QbSponsorPage]
datasets:
  QbSponsorProduct: urn:jabiz:dataset:default:QbSponsorProduct
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorPage: urn:jabiz:dataset:default:QbSponsorPage
params:
  userId: { like: QbSponsorProduct.userId, description: One sponsor's products }
results:
  productId:          { from: QbSponsorProduct.productId }
  userId:             { from: QbSponsorProduct.userId }
  name:               { from: QbSponsorProduct.name }
  description:        { from: QbSponsorProduct.description }
  image:              { from: QbSponsorProduct.image }
  price:              { from: QbSponsorProduct.price }
  currency:           { from: QbSponsorProduct.currency }
  shopUrl:            { from: QbSponsorProduct.shopUrl }
  status:             { from: QbSponsorProduct.status }
  seq:                { from: QbSponsorProduct.seq }
  takenDownAt:        { from: QbSponsorProduct.takenDownAt }
  takedownReason:     { from: QbSponsorProduct.takedownReason }
  takedownOperatorId: { from: QbSponsorProduct.takedownOperatorId }
  editedAt:           { from: QbSponsorProduct.editedAt }
  company:            { from: QbSponsor.company }
  handle:             { from: QbSponsorPage.handle }
  pageStatus:         { from: QbSponsorPage.status }
list:
  filters: [status, name, company, currency]
  sorts:   [editedAt, name, company, seq]
  defaultSort: { field: editedAt, asc: false }
  key: [productId]
permissions: [qb.admin.publication.read]
---*/
-- Every dataset is limited to the operator's sponsors for an operator's session (Q59c-2). Taking a product down and
-- putting it back are QB_PRODUCT_TAKEDOWN / QB_PRODUCT_RESTORE (headquarters) or the operator's processes.
SELECT
    pr.{{QbSponsorProduct.productId}}          AS productId,
    pr.{{QbSponsorProduct.userId}}             AS userId,
    pr.{{QbSponsorProduct.name}}               AS name,
    pr.{{QbSponsorProduct.description}}        AS description,
    pr.{{QbSponsorProduct.image}}              AS image,
    pr.{{QbSponsorProduct.price}}              AS price,
    pr.{{QbSponsorProduct.currency}}           AS currency,
    pr.{{QbSponsorProduct.shopUrl}}            AS shopUrl,
    pr.{{QbSponsorProduct.status}}             AS status,
    pr.{{QbSponsorProduct.seq}}                AS seq,
    pr.{{QbSponsorProduct.takenDownAt}}        AS takenDownAt,
    pr.{{QbSponsorProduct.takedownReason}}     AS takedownReason,
    pr.{{QbSponsorProduct.takedownOperatorId}} AS takedownOperatorId,
    pr.{{QbSponsorProduct.editedAt}}           AS editedAt,
    s.{{QbSponsor.company}}                    AS company,
    p.{{QbSponsorPage.handle}}                 AS handle,
    p.{{QbSponsorPage.status}}                 AS pageStatus
FROM {{QbSponsorProduct}} pr
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = pr.{{QbSponsorProduct.userId}}
LEFT JOIN {{QbSponsorPage}} p ON p.{{QbSponsorPage.userId}} = pr.{{QbSponsorProduct.userId}}
WHERE (CAST(:userId AS text) IS NULL OR pr.{{QbSponsorProduct.userId}} = CAST(:userId AS text))
