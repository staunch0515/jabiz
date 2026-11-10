/*---
id: qb.system.prepayments-due
description: The ended publications whose prepayments still hold something (the items of the daily job qb.prepayment-release, F-M5)
entities: [QbPrepayment, QbPublicationCard]
datasets:
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
params:
  at: { like: QbPrepayment.frozenAt, required: true, description: "The run's time" }
results:
  publicationId: { kind: { type: text, maxLength: 36 } }
  sponsorId:     { kind: { type: text, maxLength: 128 } }
list:
  sorts: [publicationId]
  defaultSort: { field: publicationId, asc: true }
  key: [publicationId]
permissions: [qb.fee.system]
---*/
-- An ended publication went on, so it has a card (its status kept with the publication's). The item reads the
-- publication again under the sponsor's wallet lock. F-M6 adds: only after the publication's last settlement.
SELECT DISTINCT p.{{QbPrepayment.publicationId}} AS publicationId, p.{{QbPrepayment.sponsorId}} AS sponsorId
FROM {{QbPrepayment}} p
JOIN {{QbPublicationCard}} c ON CAST(c.{{QbPublicationCard.publicationId}} AS text) = p.{{QbPrepayment.publicationId}}
WHERE p.{{QbPrepayment.status}} = 'OPEN'
  AND p.{{QbPrepayment.frozenAt}} <= :at
  AND c.{{QbPublicationCard.status}} = 'ENDED'
