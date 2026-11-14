/*---
id: qb.system.ai-fee-due
description: The sponsors whose charged AI jobs of a month before this one are not charged yet, one row a batch of 200 jobs (the items of the monthly job qb.ai-fee-monthly, F-M9a-2)
entities: [QbAiJob]
datasets:
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
params:
  month: { like: QbAiJob.feePeriod, required: true, description: "The month the job runs in (yyyy-MM, the fiscal year's zone): the jobs of the months before it" }
results:
  itemKey:   { kind: { type: text, maxLength: 36 } }
  sponsorId: { kind: { type: text, maxLength: 128 } }
list:
  sorts: [itemKey]
  defaultSort: { field: itemKey, asc: true }
  key: [itemKey]
permissions: [qb.fee.system]
---*/
-- The same condition as the item's read (qb.system.ai-fee-batch): finished, charged (CREDIT), with a fee, not in a
-- charge, of an earlier month. A sponsor with more jobs than an item charges (200) is given one row a batch; each item
-- takes the earliest jobs still waiting, so the batches are taken in turn. An item's key is the earliest job of its
-- batch (#699 review M1): a job whose outcome was recorded after the month's run (its fee_period still the month
-- before) gives its sponsor a key not run yet, so a run requested again for the same time (JOB_RUN_REQUEST) charges
-- it, while the items that charged stay done. The item reads everything again under the sponsor's AI and wallet locks.
SELECT CAST(b.aiJobId AS varchar(36)) AS itemKey, CAST(b.sponsorId AS varchar(128)) AS sponsorId
FROM (SELECT j.{{QbAiJob.sponsorId}} AS sponsorId, j.{{QbAiJob.aiJobId}} AS aiJobId,
             row_number() OVER (PARTITION BY j.{{QbAiJob.sponsorId}}
                                ORDER BY j.{{QbAiJob.finishedAt}}, j.{{QbAiJob.aiJobId}}) AS n
      FROM {{QbAiJob}} j
      WHERE j.{{QbAiJob.feeMode}} = 'CREDIT' AND j.{{QbAiJob.feeChargeId}} IS NULL
        AND j.{{QbAiJob.finishedAt}} IS NOT NULL AND j.{{QbAiJob.feeYen}} > 0 AND j.{{QbAiJob.feePeriod}} < :month) b
WHERE (b.n - 1) % 200 = 0
