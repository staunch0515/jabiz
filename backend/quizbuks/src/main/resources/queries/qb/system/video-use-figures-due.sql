/*---
id: qb.system.video-use-figures-due
description: >-
  F-S14: the publications using another's shared video whose figures are not final yet, the oldest first (the items
  of the job qb.video-use-figures; QB_VIDEO_USE_FIGURES_REFRESH reads each again under its lock)
entities: [QbPublicationCard, QbVideoUseFigures]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbVideoUseFigures: urn:jabiz:dataset:default:QbVideoUseFigures
results:
  publicationId: { from: QbPublicationCard.publicationId }
  wentOnAt:      { from: QbPublicationCard.wentOnAt }
list:
  sorts: [wentOnAt]
  defaultSort: { field: wentOnAt, asc: true }
  key: [publicationId]
permissions: [qb.publication.system]
---*/
-- Only the cards with a shared video (partial index, V90) whose provider is not the publisher
-- (videouse.VideoUseRules.othersVideo); finalised figures are never written again.
SELECT c.{{QbPublicationCard.publicationId}} AS publicationId, c.{{QbPublicationCard.wentOnAt}} AS wentOnAt
FROM {{QbPublicationCard}} c
LEFT JOIN {{QbVideoUseFigures}} f
       ON f.{{QbVideoUseFigures.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
WHERE c.{{QbPublicationCard.sharedVideoId}} IS NOT NULL
  AND c.{{QbPublicationCard.videoProviderId}} IS NOT NULL
  AND c.{{QbPublicationCard.videoProviderId}} <> c.{{QbPublicationCard.publisherId}}
  AND f.{{QbVideoUseFigures.finalisedAt}} IS NULL
