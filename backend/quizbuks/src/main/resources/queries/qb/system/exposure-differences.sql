/*---
id: qb.system.exposure-differences
description: >-
  F-S13: the daily reconciliation of the exposures: the publications whose successes went beyond their purchased
  exposures by more than the tolerance (max(20, the purchase / 20 rounded up)), and the ended ones that counted an
  exposure more than a minute after their end
entities: [QbPublicationCard, QbExposure]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbExposure: urn:jabiz:dataset:default:QbExposure
results:
  publicationId: { from: QbPublicationCard.publicationId }
  purchased:     { from: QbPublicationCard.exposurePurchased }
  successes:     { kind: { type: numeric, precision: 12, scale: 0 } }
  late:          { kind: { type: numeric, precision: 12, scale: 0 } }
list:
  sorts: [publicationId]
  defaultSort: { field: publicationId, asc: true }
  key: [publicationId]
permissions: [qb.publication.system]
---*/
-- Only the cards with a shared video (partial index, V90) can count. The tolerance is exposure.ExposureRules.tolerance;
-- what goes beyond the purchase is never charged (ExposureRules.billable), so a difference asks a person to look, it
-- does not change money.
SELECT c.{{QbPublicationCard.publicationId}} AS publicationId, c.{{QbPublicationCard.exposurePurchased}} AS purchased,
       x.successes AS successes, x.late AS late
FROM {{QbPublicationCard}} c
CROSS JOIN LATERAL (
    SELECT count(*) FILTER (WHERE e.{{QbExposure.countedAt}} IS NOT NULL) AS successes,
           count(*) FILTER (WHERE c.{{QbPublicationCard.status}} = 'ENDED' AND c.{{QbPublicationCard.endedAt}} IS NOT NULL
                              AND e.{{QbExposure.countedAt}} > c.{{QbPublicationCard.endedAt}} + interval '1 minute') AS late
    FROM {{QbExposure}} e
    WHERE e.{{QbExposure.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)) x
WHERE c.{{QbPublicationCard.sharedVideoId}} IS NOT NULL
  AND (x.late > 0
       OR (c.{{QbPublicationCard.exposurePurchased}} IS NOT NULL
           AND x.successes > c.{{QbPublicationCard.exposurePurchased}}
               + greatest(20, ceil(c.{{QbPublicationCard.exposurePurchased}} / 20.0))))
