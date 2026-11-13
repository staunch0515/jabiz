/*---
id: qb.system.exposure-due
description: The publications whose exposure fee is due to be settled for a month and is not yet (the items of the monthly job qb.exposure-fee, F-M8)
entities: [QbPublication, QbPublicationCard, QbExposureSettlement]
datasets:
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbExposureSettlement: urn:jabiz:dataset:default:QbExposureSettlement
params:
  period:      { like: QbExposureSettlement.period, required: true, description: "The month (yyyy-MM, the fiscal year's zone)" }
  periodStart: { like: QbExposureSettlement.settledAt, required: true, description: "The month's first instant" }
  periodEnd:   { like: QbExposureSettlement.settledAt, required: true, description: "The next month's first instant" }
results:
  publicationId: { kind: { type: text, maxLength: 36 } }
  sponsorId:     { kind: { type: text, maxLength: 128 } }
list:
  sorts: [publicationId]
  defaultSort: { field: publicationId, asc: true }
  key: [publicationId]
permissions: [qb.fee.system]
---*/
-- Due (F-M8, as the distribution fee's): a sponsor's publication with a price per exposure frozen and exposures bought,
-- on (started and approved) before the month's end, not ended before the month's start, without a final settlement;
-- and without a row of the month. The same condition is in qb.system.fee-invoices-due and qb.system.recon-prepayments
-- (EXPOSURE_UNSETTLED): change them together. The item reads everything again under the sponsor's wallet lock.
SELECT CAST(p.{{QbPublication.publicationId}} AS text) AS publicationId, p.{{QbPublication.publisherId}} AS sponsorId
FROM {{QbPublication}} p
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = p.{{QbPublication.publicationId}}
WHERE p.{{QbPublication.exposureUnit}} IS NOT NULL
  AND p.{{QbPublication.exposurePurchased}} IS NOT NULL
  AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
  -- When it went on: greatest(its start, its approval); the same expression everywhere the condition is.
  AND greatest(c.{{QbPublicationCard.startAt}}, p.{{QbPublication.approvedAt}}) < :periodEnd
  AND (c.{{QbPublicationCard.endedAt}} IS NULL OR c.{{QbPublicationCard.endedAt}} >= :periodStart)
  AND NOT EXISTS (SELECT 1 FROM {{QbExposureSettlement}} s
                  WHERE s.{{QbExposureSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                    AND (s.{{QbExposureSettlement.final}} OR s.{{QbExposureSettlement.period}} = :period))
