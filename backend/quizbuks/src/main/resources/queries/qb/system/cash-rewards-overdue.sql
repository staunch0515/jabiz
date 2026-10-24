/*---
id: qb.system.cash-rewards-overdue
description: "Cash rewards marked paid whose time to confirm ran out before the cutoff (the items of the job qb.cash-reward-overdue)"
entities: [QbCashReward]
datasets:
  QbCashReward: urn:jabiz:dataset:default:QbCashReward
params:
  cutoff: { like: QbCashReward.confirmBy, required: true, description: The scheduled time }
results:
  cashRewardId: { from: QbCashReward.cashRewardId }
  confirmBy:    { from: QbCashReward.confirmBy }
list:
  sorts: [confirmBy]
  defaultSort: { field: confirmBy, asc: true }
  key: [cashRewardId]
permissions: [qb.play.system]
---*/
-- The job's process reads the reward again under its lock and leaves one confirmed or disputed meanwhile.
SELECT r.{{QbCashReward.cashRewardId}} AS cashRewardId, r.{{QbCashReward.confirmBy}} AS confirmBy
FROM {{QbCashReward}} r
WHERE r.{{QbCashReward.status}} = 'MARKED_PAID'
  AND r.{{QbCashReward.confirmBy}} < :cutoff
