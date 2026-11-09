/*---
id: qb.system.exposure-ends
description: >-
  F-S13: the publications on (not ended) with purchased exposures whose successes reached them, the oldest first (the
  items of the job qb.exposure-ends; QB_PUBLICATION_EXPOSURE_END reads it again for its publication under its locks)
entities: [QbPublicationCard, QbExposure]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbExposure: urn:jabiz:dataset:default:QbExposure
params:
  publicationId: { kind: { type: text, maxLength: 36 }, description: "Only this publication (the end's check under the locks); all when not given" }
results:
  publicationId: { from: QbPublicationCard.publicationId }
  wentOnAt:      { from: QbPublicationCard.wentOnAt }
  purchased:     { from: QbPublicationCard.exposurePurchased }
  successes:     { kind: { type: numeric, precision: 12, scale: 0 } }
list:
  sorts: [wentOnAt]
  defaultSort: { field: wentOnAt, asc: true }
  key: [publicationId]
permissions: [qb.publication.system]
---*/
-- Through the partial index on the cards with a purchase (V98); each card's successes through the exposures' unique
-- (publication, user) index. Used up: successes >= purchased (exposure.ExposureRules.usedUp); none purchased never is.
SELECT c.{{QbPublicationCard.publicationId}} AS publicationId, c.{{QbPublicationCard.wentOnAt}} AS wentOnAt,
       c.{{QbPublicationCard.exposurePurchased}} AS purchased, x.successes AS successes
FROM {{QbPublicationCard}} c
CROSS JOIN LATERAL (
    SELECT count(*) AS successes FROM {{QbExposure}} e
    WHERE e.{{QbExposure.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
      AND e.{{QbExposure.countedAt}} IS NOT NULL) x
WHERE c.{{QbPublicationCard.exposurePurchased}} IS NOT NULL
  AND c.{{QbPublicationCard.status}} <> 'ENDED'
  AND x.successes >= c.{{QbPublicationCard.exposurePurchased}}
  AND (CAST(:publicationId AS text) IS NULL
       OR CAST(c.{{QbPublicationCard.publicationId}} AS text) = CAST(:publicationId AS text))
