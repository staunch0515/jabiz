/*---
id: qb.admin.ai-jobs
description: >-
  AI jobs of every sponsor, newest first (Q8a; covers since Q8b): their kind, state, the model asked and the one that answered (another one
  after a refusal, A-10), attempts, tokens and the failure's code and explanation; never the candidates
entities: [QbAiJob]
datasets:
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
params:
  failedOnly: { kind: { type: bool }, description: "If true, only the failed jobs" }
results:
  aiJobId:        { from: QbAiJob.aiJobId }
  externalJobId:  { from: QbAiJob.externalJobId }
  sponsorId:      { from: QbAiJob.sponsorId }
  quizId:         { from: QbAiJob.quizId }
  kind:           { from: QbAiJob.kind }
  status:         { from: QbAiJob.status }
  model:          { from: QbAiJob.model }
  servedModel:    { from: QbAiJob.servedModel }
  attempts:       { from: QbAiJob.attempts }
  candidateCount: { from: QbAiJob.candidateCount }
  invalidCount:   { from: QbAiJob.invalidCount }
  acceptedCount:  { from: QbAiJob.acceptedCount }
  inputTokens:    { from: QbAiJob.inputTokens }
  outputTokens:   { from: QbAiJob.outputTokens }
  failureCode:    { from: QbAiJob.failureCode }
  failureMessage: { from: QbAiJob.failureMessage }
  requestedAt:    { from: QbAiJob.requestedAt }
  finishedAt:     { from: QbAiJob.finishedAt }
list:
  filters: [sponsorId, kind, status, failureCode, requestedAt]
  sorts:   [requestedAt, inputTokens, outputTokens]
  defaultSort: { field: requestedAt, asc: false }
  key: [aiJobId]
permissions: [qb.admin.ai.read]
---*/
SELECT
    j.{{QbAiJob.aiJobId}}        AS aiJobId,
    j.{{QbAiJob.externalJobId}}  AS externalJobId,
    j.{{QbAiJob.sponsorId}}      AS sponsorId,
    j.{{QbAiJob.quizId}}         AS quizId,
    j.{{QbAiJob.kind}}           AS kind,
    j.{{QbAiJob.status}}         AS status,
    j.{{QbAiJob.model}}          AS model,
    j.{{QbAiJob.servedModel}}    AS servedModel,
    j.{{QbAiJob.attempts}}       AS attempts,
    j.{{QbAiJob.candidateCount}} AS candidateCount,
    j.{{QbAiJob.invalidCount}}   AS invalidCount,
    j.{{QbAiJob.acceptedCount}}  AS acceptedCount,
    j.{{QbAiJob.inputTokens}}    AS inputTokens,
    j.{{QbAiJob.outputTokens}}   AS outputTokens,
    j.{{QbAiJob.failureCode}}    AS failureCode,
    j.{{QbAiJob.failureMessage}} AS failureMessage,
    j.{{QbAiJob.requestedAt}}    AS requestedAt,
    j.{{QbAiJob.finishedAt}}     AS finishedAt
FROM {{QbAiJob}} j
WHERE (CAST(:failedOnly AS boolean) IS NOT TRUE OR j.{{QbAiJob.status}} = 'FAILED')
