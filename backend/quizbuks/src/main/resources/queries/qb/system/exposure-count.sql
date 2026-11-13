/*---
id: qb.system.exposure-count
description: >-
  F-M8: how many exposures of one publication were counted (successful) before an instant, and in all (the settlement
  of its exposure fee reads it under the sponsor's wallet lock). One row.
entities: [QbExposure]
datasets:
  QbExposure: urn:jabiz:dataset:default:QbExposure
params:
  publicationId: { like: QbExposure.publicationId, required: true, description: "The publication" }
  before:        { like: QbExposure.countedAt, required: true, description: "The month's end: counted before it" }
results:
  publicationId: { kind: { type: text, maxLength: 36 } }
  byEnd:         { kind: { type: numeric, precision: 12, scale: 0 } }
  total:         { kind: { type: numeric, precision: 12, scale: 0 } }
list:
  sorts: [publicationId]
  defaultSort: { field: publicationId, asc: true }
  key: [publicationId]
permissions: [qb.fee.system]
---*/
-- Through the exposures' (publication, user) index. A success is a counted exposure (counted_at set); the month's
-- boundary is its counting time, which is a stamp, so a settlement run late counts the same exposures.
SELECT :publicationId AS publicationId,
       count(*) FILTER (WHERE e.{{QbExposure.countedAt}} < :before) AS byEnd, count(*) AS total
FROM {{QbExposure}} e
WHERE e.{{QbExposure.publicationId}} = :publicationId AND e.{{QbExposure.countedAt}} IS NOT NULL
