/*---
id: qb.system.benefit-void-due
description: "Q89d-1: the prizes still to collect whose publication draws on a purchase suspended with its unused prizes voided (the void job's items)"
entities: [QbRedemption, QbPublicationCard, QbBenefitPurchase]
datasets:
  QbRedemption: urn:jabiz:dataset:default:QbRedemption
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbBenefitPurchase: urn:jabiz:dataset:default:QbBenefitPurchase
results:
  redemptionId: { from: QbRedemption.redemptionId }
  purchaseId:   { from: QbBenefitPurchase.purchaseId }
list:
  sorts: [redemptionId]
  defaultSort: { field: redemptionId, asc: true }
  key: [redemptionId]
permissions: [qb.benefit.system]
---*/
-- Few purchases are suspended; their publications' cards name them, and the prizes are found by publication.
SELECT r.{{QbRedemption.redemptionId}} AS redemptionId, b.{{QbBenefitPurchase.purchaseId}} AS purchaseId
FROM {{QbBenefitPurchase}} b
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.benefitPurchaseId}} = b.{{QbBenefitPurchase.purchaseId}}::text
JOIN {{QbRedemption}} r ON r.{{QbRedemption.publicationId}} = c.{{QbPublicationCard.publicationId}}
WHERE b.{{QbBenefitPurchase.status}} = 'SUSPENDED'
  AND b.{{QbBenefitPurchase.voidUnused}}
  AND r.{{QbRedemption.status}} IN ('DUE', 'CLAIMED')
