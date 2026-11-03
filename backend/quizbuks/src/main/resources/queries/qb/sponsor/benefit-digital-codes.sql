/*---
id: qb.sponsor.benefit-digital-codes
description: "Q89b-2: the codes of the pool of a publication whose digital product the caller provides: each with its sequence, hint and state; never the code itself"
entities: [QbDigitalCode, QbPublication]
datasets:
  QbDigitalCode: urn:jabiz:dataset:default:QbDigitalCode
  QbPublication: urn:jabiz:dataset:fulfiller:QbPublication
params:
  publicationId: { like: QbDigitalCode.publicationId, required: true, description: The publication }
results:
  codeId:          { from: QbDigitalCode.codeId }
  seq:             { from: QbDigitalCode.seq }
  hint:            { from: QbDigitalCode.hint }
  status:          { from: QbDigitalCode.status }
  loadedAt:        { from: QbDigitalCode.loadedAt }
  issuedAt:        { from: QbDigitalCode.issuedAt }
  revokedAt:       { from: QbDigitalCode.revokedAt }
list:
  filters: [status, loadedAt]
  sorts:   [seq, loadedAt, issuedAt]
  defaultSort: { field: seq, asc: true }
  key: [codeId]
permissions: [qb.benefit.use]
---*/
-- Only a publication the caller provides for (the publication view's scope): the content is sealed and sensitive, so it is not even selectable.
SELECT
    k.{{QbDigitalCode.codeId}}    AS codeId,
    k.{{QbDigitalCode.seq}}       AS seq,
    k.{{QbDigitalCode.hint}}      AS hint,
    k.{{QbDigitalCode.status}}    AS status,
    k.{{QbDigitalCode.loadedAt}}  AS loadedAt,
    k.{{QbDigitalCode.issuedAt}}  AS issuedAt,
    k.{{QbDigitalCode.revokedAt}} AS revokedAt
FROM {{QbDigitalCode}} k
WHERE k.{{QbDigitalCode.publicationId}} = :publicationId
  AND EXISTS (SELECT 1 FROM {{QbPublication}} p WHERE p.{{QbPublication.publicationId}} = k.{{QbDigitalCode.publicationId}})
