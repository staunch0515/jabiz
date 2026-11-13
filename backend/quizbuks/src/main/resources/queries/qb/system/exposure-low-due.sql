/*---
id: qb.system.exposure-low-due
description: >-
  F-M8: the publications on whose exposures bought are half used or more and not used up, that have not been told
  about this purchase and were not looked at since their last exposure was counted (the items of the job
  qb.exposure-low; the item decides with the real ratio, qb.exposure.low-ratio, which is never above a half)
entities: [QbPublicationCard, QbExposure, QbExposureNotice]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbExposure: urn:jabiz:dataset:default:QbExposure
  QbExposureNotice: urn:jabiz:dataset:default:QbExposureNotice
results:
  publicationId: { kind: { type: text, maxLength: 36 } }
list:
  sorts: [publicationId]
  defaultSort: { field: publicationId, asc: true }
  key: [publicationId]
permissions: [qb.publication.system]
---*/
-- On (not draft, review or ended) with a purchase; its successes through the exposures' (publication, user) index.
-- 0.5 is the largest value qb.exposure.low-ratio may have (ExposureFeeRules.MAX_LOW_RATIO): a publication more than
-- half left is never a candidate, one under half is looked at again only when a new exposure was counted (checked_used)
-- and never again once told about this number bought (notified_for).
SELECT CAST(c.{{QbPublicationCard.publicationId}} AS text) AS publicationId
FROM {{QbPublicationCard}} c
CROSS JOIN LATERAL (
    SELECT count(*) AS successes FROM {{QbExposure}} e
    WHERE e.{{QbExposure.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
      AND e.{{QbExposure.countedAt}} IS NOT NULL) x
LEFT JOIN {{QbExposureNotice}} n ON n.{{QbExposureNotice.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
WHERE c.{{QbPublicationCard.exposurePurchased}} IS NOT NULL
  AND c.{{QbPublicationCard.status}} IN ('SCHEDULED', 'LIVE', 'PAUSED', 'FUNDS_SHORT')
  AND x.successes < c.{{QbPublicationCard.exposurePurchased}}
  AND (c.{{QbPublicationCard.exposurePurchased}} - x.successes) <= c.{{QbPublicationCard.exposurePurchased}} * 0.5
  AND (n.{{QbExposureNotice.noticeId}} IS NULL
       OR (n.{{QbExposureNotice.notifiedFor}} <> c.{{QbPublicationCard.exposurePurchased}}
           AND x.successes > n.{{QbExposureNotice.checkedUsed}}))
