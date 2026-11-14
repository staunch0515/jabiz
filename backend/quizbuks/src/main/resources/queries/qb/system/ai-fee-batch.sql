/*---
id: qb.system.ai-fee-batch
description: The charged AI jobs of a sponsor waiting for a month's charge, of a month before the one given, the earliest ended first, 200 at most (an item of the monthly job qb.ai-fee-monthly reads them, F-M9a-2)
entities: [QbAiJob]
datasets:
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
params:
  sponsorId: { like: QbAiJob.sponsorId, required: true, description: The sponsor }
  month:     { like: QbAiJob.feePeriod, required: true, description: "The month the job runs in (yyyy-MM): the jobs of the months before it" }
results:
  aiJobId: { from: QbAiJob.aiJobId }
list:
  sorts: [aiJobId]
  defaultSort: { field: aiJobId, asc: true }
  key: [aiJobId]
permissions: [qb.fee.system]
---*/
-- #699 review M3: the month is the job's own (fee_period, the month its outcome was recorded in, in the fiscal year's
-- zone), as the due template qb.system.ai-fee-due reads it, never the run's clock and zone: a job is charged in the
-- month after its fee_period, late at worst, never missed. The same condition as the open set's index
-- (qb_ai_job_fee_open_idx: CREDIT, not in a charge, a fee above 0). The item loads the rows by these ids to update them.
-- The 200 are chosen inside (the platform pages the outer result, which has no limit of its own); the item reads them
-- again ordered by when they ended.
SELECT b.aiJobId AS aiJobId
FROM (SELECT j.{{QbAiJob.aiJobId}} AS aiJobId
      FROM {{QbAiJob}} j
      WHERE j.{{QbAiJob.sponsorId}} = :sponsorId AND j.{{QbAiJob.feeMode}} = 'CREDIT'
        AND j.{{QbAiJob.feeChargeId}} IS NULL AND j.{{QbAiJob.finishedAt}} IS NOT NULL AND j.{{QbAiJob.feeYen}} > 0
        AND j.{{QbAiJob.feePeriod}} < :month
      ORDER BY j.{{QbAiJob.finishedAt}}, j.{{QbAiJob.aiJobId}}
      LIMIT 200) b
