/*---
id: qb.system.distribution-final-due
description: The ended publications whose distribution fee has no final settlement yet (the items of the daily job qb.distribution-final, F-M6)
entities: [QbPublication, QbPublicationCard, QbDistributionSettlement]
datasets:
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbDistributionSettlement: urn:jabiz:dataset:default:QbDistributionSettlement
params:
  at: { like: QbDistributionSettlement.settledAt, required: true, description: "The run's time" }
results:
  publicationId: { kind: { type: text, maxLength: 36 } }
  sponsorId:     { kind: { type: text, maxLength: 128 } }
list:
  sorts: [publicationId]
  defaultSort: { field: publicationId, asc: true }
  key: [publicationId]
permissions: [qb.fee.system]
---*/
-- Ended before the run; the item settles it only once qb.distribution.final.end-grace-sec has passed since its end (and
-- does nothing until then: the next day's run settles it).
SELECT CAST(p.{{QbPublication.publicationId}} AS text) AS publicationId, p.{{QbPublication.publisherId}} AS sponsorId
FROM {{QbPublication}} p
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = p.{{QbPublication.publicationId}}
WHERE p.{{QbPublication.distributionUnit}} IS NOT NULL
  AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
  AND c.{{QbPublicationCard.status}} = 'ENDED'
  AND c.{{QbPublicationCard.endedAt}} <= :at
  AND NOT EXISTS (SELECT 1 FROM {{QbDistributionSettlement}} s
                  WHERE s.{{QbDistributionSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                    AND s.{{QbDistributionSettlement.final}})
