/*---
id: qb.operator.exposure-settlements
description: "The caller operator's exposure fee settlements (F-M8): of the publications whose fee is the operator's (the operator frozen at their submission), the month, the exposures, the price per exposure, the fee, its tax and whether it is the final one"
entities: [QbOperatorMember, QbExposureSettlement]
datasets:
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbExposureSettlement: urn:jabiz:dataset:default:QbExposureSettlement
params:
  period:        { like: QbExposureSettlement.period, description: Only this month }
  sponsorId:     { like: QbExposureSettlement.sponsorId, description: Only this Sponsor }
  publicationId: { like: QbExposureSettlement.publicationId, description: Only this publication }
results:
  settlementId:  { from: QbExposureSettlement.settlementId }
  publicationId: { from: QbExposureSettlement.publicationId }
  sponsorId:     { from: QbExposureSettlement.sponsorId }
  period:        { from: QbExposureSettlement.period }
  fromCount:     { from: QbExposureSettlement.fromCount }
  toCount:       { from: QbExposureSettlement.toCount }
  counted:       { from: QbExposureSettlement.counted }
  unit:          { from: QbExposureSettlement.unit }
  amount:        { from: QbExposureSettlement.amount }
  tax:           { from: QbExposureSettlement.tax }
  total:         { from: QbExposureSettlement.total }
  final:         { from: QbExposureSettlement.final }
  chargeId:      { from: QbExposureSettlement.chargeId }
  settledAt:     { from: QbExposureSettlement.settledAt }
list:
  sorts: [settledAt, period]
  defaultSort: { field: settledAt, asc: false }
  key: [settlementId]
permissions: [qb.operator.use]
---*/
-- Only through the caller's current membership (the view holds the caller's own), as the other operator templates:
-- the settlements whose fee is the caller's operator's (frozen at the submission), so a sponsor moved to another
-- operator since stays with the operator whose fee it was.
SELECT d.{{QbExposureSettlement.settlementId}} AS settlementId,
       d.{{QbExposureSettlement.publicationId}} AS publicationId,
       d.{{QbExposureSettlement.sponsorId}} AS sponsorId,
       d.{{QbExposureSettlement.period}} AS period,
       d.{{QbExposureSettlement.fromCount}} AS fromCount,
       d.{{QbExposureSettlement.toCount}} AS toCount,
       d.{{QbExposureSettlement.counted}} AS counted,
       d.{{QbExposureSettlement.unit}} AS unit,
       d.{{QbExposureSettlement.amount}} AS amount,
       d.{{QbExposureSettlement.tax}} AS tax,
       d.{{QbExposureSettlement.total}} AS total,
       d.{{QbExposureSettlement.final}} AS final,
       d.{{QbExposureSettlement.chargeId}} AS chargeId,
       d.{{QbExposureSettlement.settledAt}} AS settledAt
FROM {{QbOperatorMember}} me
JOIN {{QbExposureSettlement}} d
    ON d.{{QbExposureSettlement.operatorId}} = CAST(me.{{QbOperatorMember.operatorId}} AS text)
WHERE me.{{QbOperatorMember.removedAt}} IS NULL
  AND (CAST(:period AS text) IS NULL OR d.{{QbExposureSettlement.period}} = :period)
  AND (CAST(:sponsorId AS text) IS NULL OR d.{{QbExposureSettlement.sponsorId}} = :sponsorId)
  AND (CAST(:publicationId AS text) IS NULL OR d.{{QbExposureSettlement.publicationId}} = :publicationId)
