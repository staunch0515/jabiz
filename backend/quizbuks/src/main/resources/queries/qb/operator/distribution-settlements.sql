/*---
id: qb.operator.distribution-settlements
description: "The caller operator's distribution fee settlements (F-M6, review L5): of the publications whose fee is the operator's (the operator frozen at their submission), the month, the starts, the price per start and whether it is the floor, the fee, its tax and whether it is the final settlement"
entities: [QbOperatorMember, QbDistributionSettlement]
datasets:
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbDistributionSettlement: urn:jabiz:dataset:default:QbDistributionSettlement
params:
  period:        { like: QbDistributionSettlement.period, description: Only this month }
  sponsorId:     { like: QbDistributionSettlement.sponsorId, description: Only this Sponsor }
  publicationId: { like: QbDistributionSettlement.publicationId, description: Only this publication }
  atFloor:       { like: QbDistributionSettlement.atFloor, description: Only those charged the floor (true) or not (false) }
results:
  settlementId:  { from: QbDistributionSettlement.settlementId }
  publicationId: { from: QbDistributionSettlement.publicationId }
  sponsorId:     { from: QbDistributionSettlement.sponsorId }
  period:        { from: QbDistributionSettlement.period }
  starts:        { from: QbDistributionSettlement.starts }
  toStarts:      { from: QbDistributionSettlement.toStarts }
  unit:          { from: QbDistributionSettlement.unit }
  atFloor:       { from: QbDistributionSettlement.atFloor }
  amount:        { from: QbDistributionSettlement.amount }
  tax:           { from: QbDistributionSettlement.tax }
  total:         { from: QbDistributionSettlement.total }
  final:         { from: QbDistributionSettlement.final }
  settledAt:     { from: QbDistributionSettlement.settledAt }
list:
  sorts: [settledAt, period]
  defaultSort: { field: settledAt, asc: false }
  key: [settlementId]
permissions: [qb.operator.use]
---*/
-- Only through the caller's current membership (the view holds the caller's own), as the other operator templates:
-- the settlements whose fee is the caller's operator's (frozen at the submission), so a sponsor moved to another
-- operator since stays with the operator whose fee it was.
SELECT d.{{QbDistributionSettlement.settlementId}} AS settlementId,
       d.{{QbDistributionSettlement.publicationId}} AS publicationId,
       d.{{QbDistributionSettlement.sponsorId}} AS sponsorId, d.{{QbDistributionSettlement.period}} AS period,
       d.{{QbDistributionSettlement.starts}} AS starts, d.{{QbDistributionSettlement.toStarts}} AS toStarts,
       d.{{QbDistributionSettlement.unit}} AS unit, d.{{QbDistributionSettlement.atFloor}} AS atFloor,
       d.{{QbDistributionSettlement.amount}} AS amount, d.{{QbDistributionSettlement.tax}} AS tax,
       d.{{QbDistributionSettlement.total}} AS total, d.{{QbDistributionSettlement.final}} AS final,
       d.{{QbDistributionSettlement.settledAt}} AS settledAt
FROM {{QbOperatorMember}} me
JOIN {{QbDistributionSettlement}} d
    ON d.{{QbDistributionSettlement.operatorId}} = CAST(me.{{QbOperatorMember.operatorId}} AS text)
WHERE me.{{QbOperatorMember.removedAt}} IS NULL
  AND (CAST(:period AS text) IS NULL OR d.{{QbDistributionSettlement.period}} = :period)
  AND (CAST(:sponsorId AS text) IS NULL OR d.{{QbDistributionSettlement.sponsorId}} = :sponsorId)
  AND (CAST(:publicationId AS text) IS NULL OR d.{{QbDistributionSettlement.publicationId}} = :publicationId)
  AND (CAST(:atFloor AS boolean) IS NULL OR d.{{QbDistributionSettlement.atFloor}} = :atFloor)
