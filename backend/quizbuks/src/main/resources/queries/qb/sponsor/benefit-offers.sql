/*---
id: qb.sponsor.benefit-offers
description: "Q89a: the caller's benefits as their provider, shown or not, newest first"
entities: [QbBenefitOffer]
datasets:
  QbBenefitOffer: urn:jabiz:dataset:provider:QbBenefitOffer
params:
  offerId: { kind: { type: text, maxLength: 36 }, description: "Only this benefit (its editor)" }
results:
  offerId:        { from: QbBenefitOffer.offerId }
  kind:           { from: QbBenefitOffer.kind }
  name:           { from: QbBenefitOffer.name }
  description:    { from: QbBenefitOffer.description }
  image1:         { from: QbBenefitOffer.image1 }
  image2:         { from: QbBenefitOffer.image2 }
  image3:         { from: QbBenefitOffer.image3 }
  image4:         { from: QbBenefitOffer.image4 }
  unitValue:      { from: QbBenefitOffer.unitValue }
  pickupAddress:  { from: QbBenefitOffer.pickupAddress }
  pickupHours:    { from: QbBenefitOffer.pickupHours }
  terms:          { from: QbBenefitOffer.terms }
  status:         { from: QbBenefitOffer.status }
  editedAt:       { from: QbBenefitOffer.editedAt }
  revision:       { from: QbBenefitOffer.revision }
list:
  filters: [status, kind]
  sorts:   [editedAt, name]
  defaultSort: { field: editedAt, asc: false }
  key: [offerId]
permissions: [qb.benefit.use]
---*/
SELECT
    o.{{QbBenefitOffer.offerId}} AS offerId,
    o.{{QbBenefitOffer.kind}} AS kind,
    o.{{QbBenefitOffer.name}} AS name,
    o.{{QbBenefitOffer.description}} AS description,
    o.{{QbBenefitOffer.image1}} AS image1,
    o.{{QbBenefitOffer.image2}} AS image2,
    o.{{QbBenefitOffer.image3}} AS image3,
    o.{{QbBenefitOffer.image4}} AS image4,
    o.{{QbBenefitOffer.unitValue}} AS unitValue,
    o.{{QbBenefitOffer.pickupAddress}} AS pickupAddress,
    o.{{QbBenefitOffer.pickupHours}} AS pickupHours,
    o.{{QbBenefitOffer.terms}} AS terms,
    o.{{QbBenefitOffer.status}} AS status,
    o.{{QbBenefitOffer.editedAt}} AS editedAt,
    o.{{QbBenefitOffer.revision}} AS revision
FROM {{QbBenefitOffer}} o
WHERE (CAST(:offerId AS text) IS NULL OR o.{{QbBenefitOffer.offerId}} = CAST(:offerId AS text))
