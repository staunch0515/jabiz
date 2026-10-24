/*---
id: qb.system.redemption-claims-lapsed
description: "Claims from a sponsor's printed code not confirmed before the cutoff (the items of the job qb.redemption-lapse)"
entities: [QbRedemption]
datasets:
  QbRedemption: urn:jabiz:dataset:default:QbRedemption
params:
  cutoff: { like: QbRedemption.confirmBy, required: true, description: The scheduled time }
results:
  redemptionId: { from: QbRedemption.redemptionId }
  confirmBy:    { from: QbRedemption.confirmBy }
list:
  sorts: [confirmBy]
  defaultSort: { field: confirmBy, asc: true }
  key: [redemptionId]
permissions: [qb.play.system]
---*/
-- The job's process reads the claim again under its lock and leaves one confirmed meanwhile.
SELECT r.{{QbRedemption.redemptionId}} AS redemptionId, r.{{QbRedemption.confirmBy}} AS confirmBy
FROM {{QbRedemption}} r
WHERE r.{{QbRedemption.status}} = 'CLAIMED'
  AND r.{{QbRedemption.confirmBy}} <= :cutoff
