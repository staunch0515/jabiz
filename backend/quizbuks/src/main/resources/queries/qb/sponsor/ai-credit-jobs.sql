/*---
id: qb.sponsor.ai-credit-jobs
description: "The Sponsor's charged AI requests (F-M9b), newest first: what each holds while it runs, its fee once it ended (in yen, before tax), and whether the month's charge took it in"
entities: [QbAiJob]
datasets:
  QbAiJob: urn:jabiz:dataset:sponsor:QbAiJob
results:
  aiJobId:     { from: QbAiJob.aiJobId }
  kind:        { from: QbAiJob.kind }
  status:      { from: QbAiJob.status }
  requestedAt: { from: QbAiJob.requestedAt }
  finishedAt:  { from: QbAiJob.finishedAt }
  reservedYen: { from: QbAiJob.reservedYen }
  feeYen:      { from: QbAiJob.feeYen }
  feePeriod:   { from: QbAiJob.feePeriod }
  charged:     { kind: { type: bool } }
list:
  sorts: [requestedAt]
  defaultSort: { field: requestedAt, asc: false }
  key: [aiJobId]
permissions: [qb.sponsor.finance.read]
---*/
-- The caller's own jobs (the dataset's scope) charged to the AI credit; through qb_ai_job_sponsor_idx (sponsor_id,
-- requested_at DESC). What the platform took on is not shown: the sponsor pays what feeYen says.
SELECT j.{{QbAiJob.aiJobId}} AS aiJobId, j.{{QbAiJob.kind}} AS kind, j.{{QbAiJob.status}} AS status,
       j.{{QbAiJob.requestedAt}} AS requestedAt, j.{{QbAiJob.finishedAt}} AS finishedAt,
       j.{{QbAiJob.reservedYen}} AS reservedYen, j.{{QbAiJob.feeYen}} AS feeYen,
       j.{{QbAiJob.feePeriod}} AS feePeriod, j.{{QbAiJob.feeChargeId}} IS NOT NULL AS charged
FROM {{QbAiJob}} j
WHERE j.{{QbAiJob.feeMode}} = 'CREDIT'
