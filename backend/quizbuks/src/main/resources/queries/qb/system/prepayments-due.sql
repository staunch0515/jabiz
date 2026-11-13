/*---
id: qb.system.prepayments-due
description: The ended publications' prepayments that still hold something, one a publication and purpose (the items of the daily job qb.prepayment-release, F-M5, F-M6)
entities: [QbPrepayment, QbPublicationCard, QbDistributionSettlement, QbExposureSettlement]
datasets:
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbDistributionSettlement: urn:jabiz:dataset:default:QbDistributionSettlement
  QbExposureSettlement: urn:jabiz:dataset:default:QbExposureSettlement
params:
  at: { like: QbPrepayment.frozenAt, required: true, description: "The run's time" }
results:
  prepaymentId:  { kind: { type: text, maxLength: 36 } }
  publicationId: { kind: { type: text, maxLength: 36 } }
  sponsorId:     { kind: { type: text, maxLength: 128 } }
  purpose:       { kind: { type: text, maxLength: 20 } }
list:
  sorts: [prepaymentId]
  defaultSort: { field: prepaymentId, asc: true }
  key: [prepaymentId]
permissions: [qb.fee.system]
---*/
-- An ended publication went on, so it has a card (its status kept with the publication's). The item reads the
-- publication again under the sponsor's wallet lock. F-M6 (§9.7): the distribution fee's deposit only after the
-- publication's final settlement (the release reads that again under the lock), F-M8: and the exposure fee's too; the
-- other purposes once it ended.
SELECT CAST(p.{{QbPrepayment.prepaymentId}} AS text) AS prepaymentId,
       p.{{QbPrepayment.publicationId}} AS publicationId, p.{{QbPrepayment.sponsorId}} AS sponsorId,
       p.{{QbPrepayment.purpose}} AS purpose
FROM {{QbPrepayment}} p
JOIN {{QbPublicationCard}} c ON CAST(c.{{QbPublicationCard.publicationId}} AS text) = p.{{QbPrepayment.publicationId}}
WHERE p.{{QbPrepayment.status}} = 'OPEN'
  AND p.{{QbPrepayment.frozenAt}} <= :at
  AND c.{{QbPublicationCard.status}} = 'ENDED'
  AND (p.{{QbPrepayment.purpose}} NOT IN ('DISTRIBUTION', 'EXPOSURE')
       OR (p.{{QbPrepayment.purpose}} = 'DISTRIBUTION' AND EXISTS (SELECT 1 FROM {{QbDistributionSettlement}} s
                  WHERE s.{{QbDistributionSettlement.publicationId}} = p.{{QbPrepayment.publicationId}}
                    AND s.{{QbDistributionSettlement.final}}))
       OR (p.{{QbPrepayment.purpose}} = 'EXPOSURE' AND EXISTS (SELECT 1 FROM {{QbExposureSettlement}} x
                  WHERE x.{{QbExposureSettlement.publicationId}} = p.{{QbPrepayment.publicationId}}
                    AND x.{{QbExposureSettlement.final}})))
