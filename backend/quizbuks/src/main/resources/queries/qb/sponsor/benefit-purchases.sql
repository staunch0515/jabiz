/*---
id: qb.sponsor.benefit-purchases
description: "Q89a: the caller's purchases of others' benefits, newest first, with the provider's company"
entities: [QbBenefitPurchase, QbSponsor]
datasets:
  QbBenefitPurchase: urn:jabiz:dataset:sponsor:QbBenefitPurchase
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
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
list:
  filters: [status, offerId]
  sorts:   [editedAt, submittedAt, paidOn]
  defaultSort: { field: editedAt, asc: false }
  key: [purchaseId]
permissions: [qb.benefit.use]
---*/
-- The view holds the caller's purchases as the sponsor (Q89a, D-Q89-5): the benefit as saved, what the sponsor says it
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
    sp.{{QbSponsor.company}} AS otherCompany
FROM {{QbBenefitPurchase}} p
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = p.{{QbBenefitPurchase.providerId}}
WHERE (CAST(:purchaseId AS text) IS NULL OR CAST(p.{{QbBenefitPurchase.purchaseId}} AS text) = CAST(:purchaseId AS text))
