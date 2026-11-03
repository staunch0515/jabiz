/*---
id: qb.sponsor.benefit-publications
description: "Q89b-2: the publications whose prizes the caller provides (its benefits other sponsors bought), with how many codes their pools hold; one purchase's when asked"
entities: [QbPublication, QbDigitalCode, QbSponsor]
datasets:
  QbPublication: urn:jabiz:dataset:fulfiller:QbPublication
  QbDigitalCode: urn:jabiz:dataset:default:QbDigitalCode
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  purchaseId: { kind: { type: text, maxLength: 36 }, description: "Only the publications drawing on this purchase" }
results:
  publicationId:     { from: QbPublication.publicationId }
  name:              { from: QbPublication.name }
  status:            { from: QbPublication.status }
  rewardMethod:      { from: QbPublication.rewardMethod }
  digitalDelivery:   { from: QbPublication.digitalDelivery }
  maxWinners:        { from: QbPublication.maxWinners }
  benefitPurchaseId: { from: QbPublication.benefitPurchaseId }
  sponsorCompany:    { from: QbSponsor.company }
  available:         { kind: { type: numeric, precision: 9, scale: 0 } }
  issued:            { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  filters: [status]
  sorts:   [name, status]
  defaultSort: { field: name, asc: true }
  key: [publicationId]
permissions: [qb.benefit.use]
---*/
-- The view holds the publications the caller hands prizes over for (fulfillerId); the pool's codes are counted by
-- publication, never shown (their content is sealed).
SELECT
    p.{{QbPublication.publicationId}}     AS publicationId,
    p.{{QbPublication.name}}              AS name,
    p.{{QbPublication.status}}            AS status,
    p.{{QbPublication.rewardMethod}}      AS rewardMethod,
    p.{{QbPublication.digitalDelivery}}   AS digitalDelivery,
    p.{{QbPublication.maxWinners}}        AS maxWinners,
    p.{{QbPublication.benefitPurchaseId}} AS benefitPurchaseId,
    sp.{{QbSponsor.company}}              AS sponsorCompany,
    (SELECT count(*) FROM {{QbDigitalCode}} c WHERE c.{{QbDigitalCode.publicationId}} = p.{{QbPublication.publicationId}}
       AND c.{{QbDigitalCode.status}} = 'AVAILABLE') AS available,
    (SELECT count(*) FROM {{QbDigitalCode}} c WHERE c.{{QbDigitalCode.publicationId}} = p.{{QbPublication.publicationId}}
       AND c.{{QbDigitalCode.status}} = 'ISSUED') AS issued
FROM {{QbPublication}} p
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = p.{{QbPublication.publisherId}}
WHERE (CAST(:purchaseId AS text) IS NULL OR p.{{QbPublication.benefitPurchaseId}} = CAST(:purchaseId AS text))
