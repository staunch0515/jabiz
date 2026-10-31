/*---
id: qb.public.publication-products
description: >-
  The products a live publication shows (Q60b): those of its quiz (question 0) and one for some of its questions, by
  the question's number; only the publisher's own listed products, and only while their page is published
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [QbPublicationCard, QbQuizVersion, QbQuizProduct, QbSponsorProduct, QbSponsorPage]
datasets:
  QbPublicationCard: urn:jabiz:dataset:public:QbPublicationCard
  QbQuizVersion: urn:jabiz:dataset:public:QbQuizVersion
  QbQuizProduct: urn:jabiz:dataset:public:QbQuizProduct
  QbSponsorProduct: urn:jabiz:dataset:public:QbSponsorProduct
  QbSponsorPage: urn:jabiz:dataset:public:QbSponsorPage
params:
  publicationId: { like: QbPublicationCard.publicationId, required: true, description: The publication }
results:
  questionNo:  { from: QbQuizProduct.questionNo }
  seq:         { from: QbQuizProduct.seq }
  productId:   { from: QbSponsorProduct.productId }
  name:        { from: QbSponsorProduct.name }
  description: { from: QbSponsorProduct.description }
  image:       { from: QbSponsorProduct.image }
  price:       { from: QbSponsorProduct.price }
  currency:    { from: QbSponsorProduct.currency }
  shopUrl:     { from: QbSponsorProduct.shopUrl }
list:
  key: [questionNo, productId]
  sorts: [questionNo, seq]
  defaultSort: { field: questionNo, asc: true }
---*/
-- A live public card (its dataset's fixed scope), its version's quiz, the quiz's links, the publisher's own listed
-- products (a link to another's product shows nothing), and their published page.
SELECT
    l.{{QbQuizProduct.questionNo}}      AS questionNo,
    l.{{QbQuizProduct.seq}}             AS seq,
    pr.{{QbSponsorProduct.productId}}   AS productId,
    pr.{{QbSponsorProduct.name}}        AS name,
    pr.{{QbSponsorProduct.description}} AS description,
    pr.{{QbSponsorProduct.image}}       AS image,
    pr.{{QbSponsorProduct.price}}       AS price,
    pr.{{QbSponsorProduct.currency}}    AS currency,
    pr.{{QbSponsorProduct.shopUrl}}     AS shopUrl
FROM {{QbPublicationCard}} c
JOIN {{QbQuizVersion}} v ON v.{{QbQuizVersion.versionId}} = c.{{QbPublicationCard.versionId}}
JOIN {{QbQuizProduct}} l ON l.{{QbQuizProduct.quizId}} = CAST(v.{{QbQuizVersion.quizId}} AS text)
    AND l.{{QbQuizProduct.ownerId}} = c.{{QbPublicationCard.publisherId}}
JOIN {{QbSponsorProduct}} pr ON pr.{{QbSponsorProduct.productId}} = l.{{QbQuizProduct.productId}}
    AND pr.{{QbSponsorProduct.userId}} = c.{{QbPublicationCard.publisherId}}
JOIN {{QbSponsorPage}} p ON p.{{QbSponsorPage.userId}} = c.{{QbPublicationCard.publisherId}}
WHERE c.{{QbPublicationCard.publicationId}} = :publicationId
