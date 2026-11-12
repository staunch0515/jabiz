/*---
id: qb.system.distribution-due
description: The publications whose distribution fee is due to be settled for a month and is not yet (the items of the monthly job qb.distribution-fee, F-M6)
entities: [QbPublication, QbPublicationCard, QbDistributionSettlement]
datasets:
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbDistributionSettlement: urn:jabiz:dataset:default:QbDistributionSettlement
params:
  period:      { like: QbDistributionSettlement.period, required: true, description: "The month (yyyy-MM, the fiscal year's zone)" }
  periodStart: { like: QbDistributionSettlement.settledAt, required: true, description: "The month's first instant" }
  periodEnd:   { like: QbDistributionSettlement.settledAt, required: true, description: "The next month's first instant" }
results:
  publicationId: { kind: { type: text, maxLength: 36 } }
  sponsorId:     { kind: { type: text, maxLength: 128 } }
list:
  sorts: [publicationId]
  defaultSort: { field: publicationId, asc: true }
  key: [publicationId]
permissions: [qb.fee.system]
---*/
-- Due (F-M.md §9.5): a sponsor's publication with a price per start frozen, scheduled to start before the month's end,
-- not ended before the month's start, without a final settlement; and without a row of the month. The same condition
-- is in qb.system.fee-invoices-due and qb.system.recon-fee-account (DISTRIBUTION_UNSETTLED): change them together.
-- The item reads everything again under the sponsor's wallet lock.
SELECT CAST(p.{{QbPublication.publicationId}} AS text) AS publicationId, p.{{QbPublication.publisherId}} AS sponsorId
FROM {{QbPublication}} p
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = p.{{QbPublication.publicationId}}
WHERE p.{{QbPublication.distributionUnit}} IS NOT NULL
  AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
  AND c.{{QbPublicationCard.startAt}} < :periodEnd
  AND (c.{{QbPublicationCard.endedAt}} IS NULL OR c.{{QbPublicationCard.endedAt}} >= :periodStart)
  AND NOT EXISTS (SELECT 1 FROM {{QbDistributionSettlement}} s
                  WHERE s.{{QbDistributionSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                    AND (s.{{QbDistributionSettlement.final}} OR s.{{QbDistributionSettlement.period}} = :period))
