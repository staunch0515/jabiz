/*---
id: qb.sponsor.benefit-order-incidents
description: "Q89d-1: the incidents of a purchase the caller is in as its provider, newest first"
entities: [QbBenefitIncident, QbBenefitPurchase]
datasets:
  QbBenefitIncident: urn:jabiz:dataset:default:QbBenefitIncident
  QbBenefitPurchase: urn:jabiz:dataset:provider:QbBenefitPurchase
params:
  purchaseId: { kind: { type: text, maxLength: 36 }, required: true, description: "The purchase" }
results:
  incidentId:   { from: QbBenefitIncident.incidentId }
  purchaseId:   { from: QbBenefitIncident.purchaseId }
  redemptionId: { from: QbBenefitIncident.redemptionId }
  kind:         { from: QbBenefitIncident.kind }
  note:         { from: QbBenefitIncident.note }
  recordedAt:   { from: QbBenefitIncident.recordedAt }
  recordedBy:   { kind: { type: text, maxLength: 10 } }
  resolution:   { from: QbBenefitIncident.resolution }
  resolvedAt:   { from: QbBenefitIncident.resolvedAt }
  resolvedBy:   { kind: { type: text, maxLength: 10 } }
list:
  sorts: [recordedAt]
  defaultSort: { field: recordedAt, asc: false }
  key: [incidentId]
permissions: [qb.benefit.use]
---*/
-- Only a purchase the caller is in (the purchase view's scope); who wrote what shows as a side, never an id.
SELECT
    i.{{QbBenefitIncident.incidentId}} AS incidentId,
    i.{{QbBenefitIncident.purchaseId}} AS purchaseId,
    i.{{QbBenefitIncident.redemptionId}} AS redemptionId,
    i.{{QbBenefitIncident.kind}} AS kind,
    i.{{QbBenefitIncident.note}} AS note,
    i.{{QbBenefitIncident.recordedAt}} AS recordedAt,
    CASE WHEN i.{{QbBenefitIncident.recordedBy}} = p.{{QbBenefitPurchase.sponsorId}} THEN 'SPONSOR' ELSE 'PROVIDER' END
        AS recordedBy,
    i.{{QbBenefitIncident.resolution}} AS resolution,
    i.{{QbBenefitIncident.resolvedAt}} AS resolvedAt,
    CASE WHEN i.{{QbBenefitIncident.resolvedBy}} IS NULL THEN NULL
         WHEN i.{{QbBenefitIncident.resolvedBy}} = p.{{QbBenefitPurchase.sponsorId}} THEN 'SPONSOR' ELSE 'PROVIDER' END
        AS resolvedBy
FROM {{QbBenefitIncident}} i
JOIN {{QbBenefitPurchase}} p ON p.{{QbBenefitPurchase.purchaseId}}::text = i.{{QbBenefitIncident.purchaseId}}
WHERE i.{{QbBenefitIncident.purchaseId}} = CAST(:purchaseId AS text)
