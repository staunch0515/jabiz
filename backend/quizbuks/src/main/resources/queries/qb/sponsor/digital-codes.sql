/*---
id: qb.sponsor.digital-codes
description: "The codes of a publication's pool of digital prizes (Q33): each with its sequence, hint and state; never the code itself"
entities: [QbDigitalCode]
datasets:
  QbDigitalCode: urn:jabiz:dataset:sponsor:QbDigitalCode
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
permissions: [qb.publication.write]
---*/
-- The sponsor's own pool (the dataset's scope): the content is sealed and sensitive, so it is not even selectable.
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
