/*---
id: qb.system.shared-video-ends
description: >-
  F-S12: the publications on (not ended) whose shared video was taken down, the oldest first (the items of the job
  qb.shared-video-ends)
entities: [QbPublicationCard, QbSharedVideo]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSharedVideo: urn:jabiz:dataset:default:QbSharedVideo
results:
  publicationId: { from: QbPublicationCard.publicationId }
  wentOnAt:      { from: QbPublicationCard.wentOnAt }
list:
  sorts: [wentOnAt]
  defaultSort: { field: wentOnAt, asc: true }
  key: [publicationId]
permissions: [qb.publication.system]
---*/
-- Through the partial index on the card's shared video (V85). QB_PUBLICATION_SHARED_VIDEO_END reads the publication
-- again under its locks and ends it only when it still runs.
SELECT c.{{QbPublicationCard.publicationId}} AS publicationId, c.{{QbPublicationCard.wentOnAt}} AS wentOnAt
FROM {{QbPublicationCard}} c
JOIN {{QbSharedVideo}} v ON v.{{QbSharedVideo.sharedVideoId}} = c.{{QbPublicationCard.sharedVideoId}}
WHERE c.{{QbPublicationCard.sharedVideoId}} IS NOT NULL
  AND c.{{QbPublicationCard.status}} <> 'ENDED'
  AND v.{{QbSharedVideo.status}} = 'TAKEN_DOWN'
