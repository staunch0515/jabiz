/*---
id: qb.system.cash-rewards-unpaid
description: "Cash rewards still owed past their time to pay, whose sponsor was not reminded yet (the items of the job qb.cash-reward-remind)"
entities: [QbCashReward]
datasets:
  QbCashReward: urn:jabiz:dataset:default:QbCashReward
params:
  cutoff: { like: QbCashReward.payBy, required: true, description: The scheduled time }
results:
  cashRewardId: { from: QbCashReward.cashRewardId }
  payBy:        { from: QbCashReward.payBy }
list:
  sorts: [payBy]
  defaultSort: { field: payBy, asc: true }
  key: [cashRewardId]
permissions: [qb.play.system]
---*/
-- The job's process reads the reward again under its lock and reminds once (remindedAt).
SELECT r.{{QbCashReward.cashRewardId}} AS cashRewardId, r.{{QbCashReward.payBy}} AS payBy
FROM {{QbCashReward}} r
WHERE r.{{QbCashReward.status}} = 'DUE'
  AND r.{{QbCashReward.remindedAt}} IS NULL
  AND r.{{QbCashReward.payBy}} < :cutoff
