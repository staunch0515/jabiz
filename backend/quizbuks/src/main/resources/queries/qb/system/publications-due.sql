/*---
id: qb.system.publications-due
description: Publications whose start or end has come by the given time, earliest first (the items of the job qb.publication-tick)
entities: [QbPublicationSchedule]
datasets:
  QbPublicationSchedule: urn:jabiz:dataset:default:QbPublicationSchedule
params:
  at: { like: QbPublicationSchedule.nextDueAt, required: true, description: The scheduled time of the run }
results:
  publicationId: { from: QbPublicationSchedule.publicationId }
  nextDueAt:     { from: QbPublicationSchedule.nextDueAt }
list:
  sorts: [nextDueAt]
  defaultSort: { field: nextDueAt, asc: true }
  key: [publicationId]
permissions: [qb.publication.system]
---*/
-- Through the partial index on the due time (V4). The schedule is kept with the publication's state under its lock;
-- QB_PUBLICATION_ADVANCE reads the publication again under that lock and moves it only as its times say.
SELECT s.{{QbPublicationSchedule.publicationId}} AS publicationId, s.{{QbPublicationSchedule.nextDueAt}} AS nextDueAt
FROM {{QbPublicationSchedule}} s
WHERE s.{{QbPublicationSchedule.nextDueAt}} <= :at
