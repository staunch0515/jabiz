/*---
id: qb.system.redemptions-expired
description: "Prizes not collected (due, or claimed and never confirmed) whose time ran out before the cutoff (the items of the job qb.redemption-expire)"
entities: [QbRedemption]
datasets:
  QbRedemption: urn:jabiz:dataset:default:QbRedemption
params:
  cutoff: { like: QbRedemption.expiresAt, required: true, description: The scheduled time }
results:
  redemptionId: { from: QbRedemption.redemptionId }
  expiresAt:    { from: QbRedemption.expiresAt }
list:
  sorts: [expiresAt]
  defaultSort: { field: expiresAt, asc: true }
  key: [redemptionId]
permissions: [qb.play.system]
---*/
-- The job's process reads the prize again under its lock and leaves one collected meanwhile.
SELECT r.{{QbRedemption.redemptionId}} AS redemptionId, r.{{QbRedemption.expiresAt}} AS expiresAt
FROM {{QbRedemption}} r
WHERE r.{{QbRedemption.status}} IN ('DUE', 'CLAIMED')
  AND r.{{QbRedemption.expiresAt}} < :cutoff
