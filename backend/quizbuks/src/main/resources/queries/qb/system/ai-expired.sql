/*---
id: qb.system.ai-expired
description: AI jobs whose candidates waited for review past their expiry (the items of the job qb.ai-expire)
entities: [QbAiJob]
datasets:
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
params:
  at: { like: QbAiJob.expiresAt, required: true, description: The scheduled time }
results:
  aiJobId:   { from: QbAiJob.aiJobId }
  sponsorId: { from: QbAiJob.sponsorId }
  expiresAt: { from: QbAiJob.expiresAt }
list:
  sorts: [expiresAt]
  defaultSort: { field: expiresAt, asc: true }
  key: [aiJobId]
permissions: [qb.ai.system]
---*/
-- Through the partial index on the expiry of ready jobs (V16). Each item's process reads the job again under the
-- sponsor's lock and leaves one accepted or discarded meanwhile.
SELECT j.{{QbAiJob.aiJobId}} AS aiJobId, j.{{QbAiJob.sponsorId}} AS sponsorId, j.{{QbAiJob.expiresAt}} AS expiresAt
FROM {{QbAiJob}} j
WHERE j.{{QbAiJob.status}} = 'READY'
  AND j.{{QbAiJob.expiresAt}} <= :at
