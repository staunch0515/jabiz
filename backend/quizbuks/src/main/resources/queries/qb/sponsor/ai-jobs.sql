/*---
id: qb.sponsor.ai-jobs
description: >-
  The sponsor's AI jobs of one of their quizzes (Q8a, M-26), newest first, with the candidates while they wait for
  review; not the model that answered (administrators only, A-10)
entities: [QbAiJob]
datasets:
  QbAiJob: urn:jabiz:dataset:sponsor:QbAiJob
params:
  quizId: { like: QbAiJob.quizId, required: true, description: The quiz }
results:
  aiJobId:        { from: QbAiJob.aiJobId }
  externalJobId:  { from: QbAiJob.externalJobId }
  quizId:         { from: QbAiJob.quizId }
  status:         { from: QbAiJob.status }
  params:         { from: QbAiJob.params }
  candidates:     { from: QbAiJob.candidates }
  candidateCount: { from: QbAiJob.candidateCount }
  invalidCount:   { from: QbAiJob.invalidCount }
  acceptedCount:  { from: QbAiJob.acceptedCount }
  failureCode:    { from: QbAiJob.failureCode }
  requestedAt:    { from: QbAiJob.requestedAt }
  finishedAt:     { from: QbAiJob.finishedAt }
  expiresAt:      { from: QbAiJob.expiresAt }
list:
  sorts: [requestedAt]
  defaultSort: { field: requestedAt, asc: false }
  key: [aiJobId]
permissions: [qb.ai.use]
---*/
-- The sponsor's own rows only (the dataset's scope); through the index on (quiz_id, requested_at).
SELECT
    j.{{QbAiJob.aiJobId}}        AS aiJobId,
    j.{{QbAiJob.externalJobId}}  AS externalJobId,
    j.{{QbAiJob.quizId}}         AS quizId,
    j.{{QbAiJob.status}}         AS status,
    j.{{QbAiJob.params}}         AS params,
    j.{{QbAiJob.candidates}}     AS candidates,
    j.{{QbAiJob.candidateCount}} AS candidateCount,
    j.{{QbAiJob.invalidCount}}   AS invalidCount,
    j.{{QbAiJob.acceptedCount}}  AS acceptedCount,
    j.{{QbAiJob.failureCode}}    AS failureCode,
    j.{{QbAiJob.requestedAt}}    AS requestedAt,
    j.{{QbAiJob.finishedAt}}     AS finishedAt,
    j.{{QbAiJob.expiresAt}}      AS expiresAt
FROM {{QbAiJob}} j
WHERE j.{{QbAiJob.quizId}} = :quizId
