/*---
id: qb.sponsor.invitation-summary
description: How many addresses of one of the sponsor's publications were invited, written to, started and answered (Q25)
entities: [QbInvitation]
datasets:
  QbInvitation: urn:jabiz:dataset:sponsor:QbInvitation
params:
  publicationId: { like: QbInvitation.publicationId, required: true, description: The publication }
results:
  publicationId: { from: QbInvitation.publicationId }
  total:         { kind: { type: numeric, precision: 9, scale: 0 } }
  added:         { kind: { type: numeric, precision: 9, scale: 0 } }
  queued:        { kind: { type: numeric, precision: 9, scale: 0 } }
  sent:          { kind: { type: numeric, precision: 9, scale: 0 } }
  started:       { kind: { type: numeric, precision: 9, scale: 0 } }
  answered:      { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  key: [publicationId]
permissions: [qb.publication.write]
---*/
-- One row, zeros for a publication without a list (the sponsor's own only, by the dataset's scope).
SELECT
    CAST(:publicationId AS uuid) AS publicationId,
    count(*)                                                        AS total,
    count(*) FILTER (WHERE i.{{QbInvitation.status}} = 'ADDED')    AS added,
    count(*) FILTER (WHERE i.{{QbInvitation.status}} = 'QUEUED')   AS queued,
    count(*) FILTER (WHERE i.{{QbInvitation.status}} = 'SENT')     AS sent,
    count(*) FILTER (WHERE i.{{QbInvitation.status}} = 'STARTED')  AS started,
    count(*) FILTER (WHERE i.{{QbInvitation.status}} = 'ANSWERED') AS answered
FROM {{QbInvitation}} i
WHERE i.{{QbInvitation.publicationId}} = :publicationId
