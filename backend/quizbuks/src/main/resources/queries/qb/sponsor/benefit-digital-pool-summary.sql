/*---
id: qb.sponsor.benefit-digital-pool-summary
description: "Q89b-2: how the pool of a publication whose digital product the caller provides stands: codes available, issued and taken back"
entities: [QbDigitalCode, QbPublication]
datasets:
  QbDigitalCode: urn:jabiz:dataset:default:QbDigitalCode
  QbPublication: urn:jabiz:dataset:fulfiller:QbPublication
params:
  publicationId: { like: QbDigitalCode.publicationId, required: true, description: The publication }
results:
  available: { kind: { type: numeric, precision: 9, scale: 0 } }
  issued:    { kind: { type: numeric, precision: 9, scale: 0 } }
  revoked:   { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  key: [available, issued, revoked]
permissions: [qb.benefit.use]
---*/
SELECT
    count(*) FILTER (WHERE k.{{QbDigitalCode.status}} = 'AVAILABLE') AS available,
    count(*) FILTER (WHERE k.{{QbDigitalCode.status}} = 'ISSUED')    AS issued,
    count(*) FILTER (WHERE k.{{QbDigitalCode.status}} = 'REVOKED')   AS revoked
FROM {{QbDigitalCode}} k
-- Only a publication the caller provides for (the publication view's scope): the codes are the provider's.
WHERE k.{{QbDigitalCode.publicationId}} = :publicationId
  AND EXISTS (SELECT 1 FROM {{QbPublication}} p WHERE p.{{QbPublication.publicationId}} = k.{{QbDigitalCode.publicationId}})
