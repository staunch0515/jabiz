/*---
id: qb.sponsor.site-profile
description: >-
  The sponsor's drafts from their website (F-C7), newest first: the state, the request as asked, and, while the draft
  waits for the sponsor ({@code READY}), the draft itself (the package, where each field came from, what the site did
  not show, the articles and questions of the "get to know us" quiz), until it expires; never the model that answered
entities: [QbAiJob]
datasets:
  QbAiJob: urn:jabiz:dataset:sponsor:QbAiJob
results:
  aiJobId:        { from: QbAiJob.aiJobId }
  externalJobId:  { from: QbAiJob.externalJobId }
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
-- The sponsor's own rows only (the dataset's scope).
SELECT
    j.{{QbAiJob.aiJobId}}        AS aiJobId,
    j.{{QbAiJob.externalJobId}}  AS externalJobId,
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
WHERE j.{{QbAiJob.kind}} = 'SITE_PROFILE'
