/*---
id: qb.sponsor.broadcast-publications
description: >-
  The sponsor's publications a notice may name (M-60, Q7-10) - scheduled or live, not yet at their end - with their
  regions and how many notices each had
entities: [QbPublicationCard, QbPublicationOwner, QbBroadcast]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbPublicationOwner: urn:jabiz:dataset:me:QbPublicationOwner
  QbBroadcast: urn:jabiz:dataset:sponsor:QbBroadcast
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The client's now" }
results:
  publicationId: { from: QbPublicationCard.publicationId }
  name:          { from: QbPublicationCard.name }
  status:        { from: QbPublicationCard.status }
  regions:       { from: QbPublicationCard.regions }
  startAt:       { from: QbPublicationCard.startAt }
  endAt:         { from: QbPublicationCard.endAt }
  notices:       { kind: { type: numeric, precision: 3, scale: 0 } }
list:
  sorts: [endAt, name]
  defaultSort: { field: endAt, asc: true }
  key: [publicationId]
permissions: [qb.broadcast.write]
---*/
-- The caller's own cards only (QbPublicationOwner is limited to the caller). Notices that count against a
-- publication's limit: scheduled, sent and withdrawn ones (messaging.BroadcastRules). Saving and sending check
-- everything again; this only offers the choice.
SELECT
    c.{{QbPublicationCard.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}}          AS name,
    c.{{QbPublicationCard.status}}        AS status,
    c.{{QbPublicationCard.regions}}       AS regions,
    c.{{QbPublicationCard.startAt}}       AS startAt,
    c.{{QbPublicationCard.endAt}}         AS endAt,
    (SELECT count(*) FROM {{QbBroadcast}} b
     WHERE b.{{QbBroadcast.publicationId}} = c.{{QbPublicationCard.publicationId}}
       AND b.{{QbBroadcast.status}} IN ('SCHEDULED', 'SENT', 'WITHDRAWN')) AS notices
FROM {{QbPublicationCard}} c
JOIN {{QbPublicationOwner}} own ON own.{{QbPublicationOwner.publicationId}} = c.{{QbPublicationCard.publicationId}}
WHERE c.{{QbPublicationCard.status}} IN ('SCHEDULED', 'LIVE') AND :at < c.{{QbPublicationCard.endAt}}
  -- Q25: an invited publication is reached from its mail only, never announced.
  AND c.{{QbPublicationCard.audience}} = 'PUBLIC'
