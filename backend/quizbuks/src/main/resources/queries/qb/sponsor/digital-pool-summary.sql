/*---
id: qb.sponsor.digital-pool-summary
description: "How a publication's pool of digital prizes stands (Q33): codes available, issued and taken back"
entities: [QbDigitalCode]
datasets:
  QbDigitalCode: urn:jabiz:dataset:sponsor:QbDigitalCode
params:
  publicationId: { like: QbDigitalCode.publicationId, required: true, description: The publication }
results:
  available: { kind: { type: numeric, precision: 9, scale: 0 } }
  issued:    { kind: { type: numeric, precision: 9, scale: 0 } }
  revoked:   { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  key: [available, issued, revoked]
permissions: [qb.publication.write]
---*/
SELECT
    count(*) FILTER (WHERE k.{{QbDigitalCode.status}} = 'AVAILABLE') AS available,
    count(*) FILTER (WHERE k.{{QbDigitalCode.status}} = 'ISSUED')    AS issued,
    count(*) FILTER (WHERE k.{{QbDigitalCode.status}} = 'REVOKED')   AS revoked
FROM {{QbDigitalCode}} k
WHERE k.{{QbDigitalCode.publicationId}} = :publicationId
