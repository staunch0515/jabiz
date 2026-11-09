/*---
id: qb.admin.shared-video-uses
description: >-
  F-C3: the publications that went on with a shared video (their cards froze it), newest first, with their publisher,
  their state and, once ended, why and when: after a takedown, the publications it ended. Headquarters reads every
  sponsor's, an operator its own sponsors' publications only
entities: [QbPublicationCard, QbPublication]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbPublication: urn:jabiz:dataset:default:QbPublication
params:
  sharedVideoId: { kind: { type: text, maxLength: 36 }, description: "The shared video (required: none lists nothing)" }
results:
  publicationId: { from: QbPublicationCard.publicationId }
  name:          { from: QbPublicationCard.name }
  publisherKind: { from: QbPublicationCard.publisherKind }
  publisherId:   { from: QbPublicationCard.publisherId }
  status:        { from: QbPublication.status }
  startAt:       { from: QbPublicationCard.startAt }
  endAt:         { from: QbPublicationCard.endAt }
  endReason:     { from: QbPublication.endReason }
  endedAt:       { from: QbPublication.endedAt }
list:
  filters: [status, endReason, publisherId]
  sorts:   [startAt, endedAt]
  defaultSort: { field: startAt, asc: false }
  key: [publicationId]
permissions: [qb.admin.shared-video.read]
---*/
-- Found by the card's frozen video (partial index on it); the state, the end's reason and time are the publication's
-- current version's. An operator's session reads its own sponsors' cards and publications only (Q59c-2), whoever
-- provided the video: the publications it looks after that a takedown ended.
SELECT
    c.{{QbPublicationCard.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}}          AS name,
    c.{{QbPublicationCard.publisherKind}} AS publisherKind,
    c.{{QbPublicationCard.publisherId}}   AS publisherId,
    p.{{QbPublication.status}}            AS status,
    c.{{QbPublicationCard.startAt}}       AS startAt,
    c.{{QbPublicationCard.endAt}}         AS endAt,
    p.{{QbPublication.endReason}}         AS endReason,
    p.{{QbPublication.endedAt}}           AS endedAt
FROM {{QbPublicationCard}} c
JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = c.{{QbPublicationCard.publicationId}}
WHERE c.{{QbPublicationCard.sharedVideoId}} = CAST(:sharedVideoId AS text)
