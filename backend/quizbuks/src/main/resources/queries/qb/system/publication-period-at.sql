/*---
id: qb.system.publication-period-at
description: "A publication's start and end as they were at a time (F-M7, N5: the period an audience fee frozen before F-M7 was billed for, read at the time it was frozen)"
entities: [QbPublication]
datasets:
  QbPublication: urn:jabiz:dataset:default:QbPublication
timeSlice: { asOf: at }
params:
  publicationId: { like: QbPublication.publicationId, required: true, description: The publication }
  at:            { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time it is read at }
results:
  startAt: { from: QbPublication.startAt }
  endAt:   { from: QbPublication.endAt }
permissions: [qb.fee.system]
---*/
SELECT p.{{QbPublication.startAt}} AS startAt, p.{{QbPublication.endAt}} AS endAt
FROM {{QbPublication}} p
WHERE p.{{QbPublication.publicationId}} = :publicationId
