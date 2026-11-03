/*---
id: qb.sponsor.benefit-orders
description: "Q89a: the purchases other sponsors made of the caller's benefits, newest first, with the sponsor's company"
entities: [QbBenefitPurchase, QbSponsor, QbBenefitAllocation, QbPublicationCard, QbRedemption, QbDigitalPrize]
datasets:
  QbBenefitPurchase: urn:jabiz:dataset:provider:QbBenefitPurchase
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbBenefitAllocation: urn:jabiz:dataset:default:QbBenefitAllocation
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbRedemption: urn:jabiz:dataset:default:QbRedemption
  QbDigitalPrize: urn:jabiz:dataset:default:QbDigitalPrize
params:
  purchaseId: { kind: { type: text, maxLength: 36 }, description: "Only this purchase (its page)" }
results:
  purchaseId:        { from: QbBenefitPurchase.purchaseId }
  offerId:           { from: QbBenefitPurchase.offerId }
  kind:              { from: QbBenefitPurchase.kind }
  title:             { from: QbBenefitPurchase.title }
  unitValue:         { from: QbBenefitPurchase.unitValue }
  pickupAddress:     { from: QbBenefitPurchase.pickupAddress }
  pickupHours:       { from: QbBenefitPurchase.pickupHours }
  terms:             { from: QbBenefitPurchase.terms }
  quantity:          { from: QbBenefitPurchase.quantity }
  paidAmount:        { from: QbBenefitPurchase.paidAmount }
  paidOn:            { from: QbBenefitPurchase.paidOn }
  proof1:            { from: QbBenefitPurchase.proof1 }
  proof2:            { from: QbBenefitPurchase.proof2 }
  proof3:            { from: QbBenefitPurchase.proof3 }
  proof4:            { from: QbBenefitPurchase.proof4 }
  proof5:            { from: QbBenefitPurchase.proof5 }
  note:              { from: QbBenefitPurchase.note }
  status:            { from: QbBenefitPurchase.status }
  submittedAt:       { from: QbBenefitPurchase.submittedAt }
  confirmedAt:       { from: QbBenefitPurchase.confirmedAt }
  confirmedRevision: { from: QbBenefitPurchase.confirmedRevision }
  declinedAt:        { from: QbBenefitPurchase.declinedAt }
  declineReason:     { from: QbBenefitPurchase.declineReason }
  cancelledAt:       { from: QbBenefitPurchase.cancelledAt }
  editedAt:          { from: QbBenefitPurchase.editedAt }
  revision:          { from: QbBenefitPurchase.revision }
  otherCompany:      { from: QbSponsor.company }
  allocated:         { kind: { type: numeric, precision: 9, scale: 0 } }
  given:             { kind: { type: numeric, precision: 9, scale: 0 } }
  issued:            { kind: { type: numeric, precision: 9, scale: 0 } }
  collected:         { kind: { type: numeric, precision: 9, scale: 0 } }
  expired:           { kind: { type: numeric, precision: 9, scale: 0 } }
  voided:            { kind: { type: numeric, precision: 9, scale: 0 } }
  suspendedAt:       { from: QbBenefitPurchase.suspendedAt }
  suspendReason:     { from: QbBenefitPurchase.suspendReason }
  voidUnused:        { from: QbBenefitPurchase.voidUnused }
  resumedAt:         { from: QbBenefitPurchase.resumedAt }
list:
  filters: [status, offerId]
  sorts:   [editedAt, submittedAt, paidOn]
  defaultSort: { field: editedAt, asc: false }
  key: [purchaseId]
permissions: [qb.benefit.use]
---*/
-- The view holds the caller's purchases as the provider (Q89a, D-Q89-5): the benefit as saved, what the sponsor says it
-- paid, and the provider's answer; the other side shows by its company.
SELECT
    p.{{QbBenefitPurchase.purchaseId}} AS purchaseId,
    p.{{QbBenefitPurchase.offerId}} AS offerId,
    p.{{QbBenefitPurchase.kind}} AS kind,
    p.{{QbBenefitPurchase.title}} AS title,
    p.{{QbBenefitPurchase.unitValue}} AS unitValue,
    p.{{QbBenefitPurchase.pickupAddress}} AS pickupAddress,
    p.{{QbBenefitPurchase.pickupHours}} AS pickupHours,
    p.{{QbBenefitPurchase.terms}} AS terms,
    p.{{QbBenefitPurchase.quantity}} AS quantity,
    p.{{QbBenefitPurchase.paidAmount}} AS paidAmount,
    p.{{QbBenefitPurchase.paidOn}} AS paidOn,
    p.{{QbBenefitPurchase.proof1}} AS proof1,
    p.{{QbBenefitPurchase.proof2}} AS proof2,
    p.{{QbBenefitPurchase.proof3}} AS proof3,
    p.{{QbBenefitPurchase.proof4}} AS proof4,
    p.{{QbBenefitPurchase.proof5}} AS proof5,
    p.{{QbBenefitPurchase.note}} AS note,
    p.{{QbBenefitPurchase.status}} AS status,
    p.{{QbBenefitPurchase.submittedAt}} AS submittedAt,
    p.{{QbBenefitPurchase.confirmedAt}} AS confirmedAt,
    p.{{QbBenefitPurchase.confirmedRevision}} AS confirmedRevision,
    p.{{QbBenefitPurchase.declinedAt}} AS declinedAt,
    p.{{QbBenefitPurchase.declineReason}} AS declineReason,
    p.{{QbBenefitPurchase.cancelledAt}} AS cancelledAt,
    p.{{QbBenefitPurchase.editedAt}} AS editedAt,
    p.{{QbBenefitPurchase.revision}} AS revision,
    -- Q89d-1 (D-Q89-13): what the purchase became: the shares held by running publications and given by ended ones,
    -- and its prizes (collected at the place, or digital) by where they stand.
    (SELECT coalesce(sum(a.{{QbBenefitAllocation.quantity}}), 0) FROM {{QbBenefitAllocation}} a
       WHERE a.{{QbBenefitAllocation.purchaseId}} = p.{{QbBenefitPurchase.purchaseId}}::text
         AND a.{{QbBenefitAllocation.status}} = 'OPEN') AS allocated,
    (SELECT coalesce(sum(a.{{QbBenefitAllocation.used}}), 0) FROM {{QbBenefitAllocation}} a
       WHERE a.{{QbBenefitAllocation.purchaseId}} = p.{{QbBenefitPurchase.purchaseId}}::text
         AND a.{{QbBenefitAllocation.status}} = 'RELEASED') AS given,
    prizes.issued AS issued,
    prizes.collected AS collected,
    prizes.expired AS expired,
    prizes.voided AS voided,
    p.{{QbBenefitPurchase.suspendedAt}} AS suspendedAt,
    p.{{QbBenefitPurchase.suspendReason}} AS suspendReason,
    p.{{QbBenefitPurchase.voidUnused}} AS voidUnused,
    p.{{QbBenefitPurchase.resumedAt}} AS resumedAt,
    sp.{{QbSponsor.company}} AS otherCompany
FROM {{QbBenefitPurchase}} p
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = p.{{QbBenefitPurchase.sponsorId}}
LEFT JOIN LATERAL (
    SELECT count(*) AS issued,
           count(*) FILTER (WHERE x.status IN ('REDEEMED', 'DELIVERED', 'CONFIRMED')) AS collected,
           count(*) FILTER (WHERE x.status = 'EXPIRED') AS expired,
           count(*) FILTER (WHERE x.status = 'VOID') AS voided
    FROM (
        SELECT r.{{QbRedemption.status}} AS status FROM {{QbPublicationCard}} c
        JOIN {{QbRedemption}} r ON r.{{QbRedemption.publicationId}} = c.{{QbPublicationCard.publicationId}}
        WHERE c.{{QbPublicationCard.benefitPurchaseId}} = p.{{QbBenefitPurchase.purchaseId}}::text
        UNION ALL
        SELECT d.{{QbDigitalPrize.status}} FROM {{QbPublicationCard}} c
        JOIN {{QbDigitalPrize}} d ON d.{{QbDigitalPrize.publicationId}} = c.{{QbPublicationCard.publicationId}}
        WHERE c.{{QbPublicationCard.benefitPurchaseId}} = p.{{QbBenefitPurchase.purchaseId}}::text
    ) x
) prizes ON true
WHERE (CAST(:purchaseId AS text) IS NULL OR CAST(p.{{QbBenefitPurchase.purchaseId}} AS text) = CAST(:purchaseId AS text))
