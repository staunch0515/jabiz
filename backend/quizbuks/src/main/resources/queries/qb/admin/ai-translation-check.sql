/*---
id: qb.admin.ai-translation-check
description: >-
  Q73d-2: the latest AI comparison of a publication's translations in one language with their base texts, whoever of
  its reviewers asked for it, with the verdicts while they are kept; only of a publication the reader may review (the
  publication's dataset is the reviewer's, an operator's member's limited to the operator's sponsors)
entities: [QbAiJob, QbPublication]
datasets:
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
  QbPublication: urn:jabiz:dataset:default:QbPublication
params:
  publicationId: { like: QbPublication.publicationId, required: true, description: The publication }
  language:      { kind: { type: text, maxLength: 2 }, required: true, description: The translated language }
results:
  aiJobId:        { from: QbAiJob.aiJobId }
  status:         { from: QbAiJob.status }
  candidates:     { from: QbAiJob.candidates }
  candidateCount: { from: QbAiJob.candidateCount }
  invalidCount:   { from: QbAiJob.invalidCount }
  failureCode:    { from: QbAiJob.failureCode }
  requestedAt:    { from: QbAiJob.requestedAt }
  finishedAt:     { from: QbAiJob.finishedAt }
  expiresAt:      { from: QbAiJob.expiresAt }
list:
  sorts: [requestedAt]
  defaultSort: { field: requestedAt, asc: false }
  key: [aiJobId]
permissions: [qb.review.publication]
---*/
-- The jobs of the publication's quiz (the index on quiz_id), of this publication and language by their parameters.
SELECT
    j.{{QbAiJob.aiJobId}}        AS aiJobId,
    j.{{QbAiJob.status}}         AS status,
    j.{{QbAiJob.candidates}}     AS candidates,
    j.{{QbAiJob.candidateCount}} AS candidateCount,
    j.{{QbAiJob.invalidCount}}   AS invalidCount,
    j.{{QbAiJob.failureCode}}    AS failureCode,
    j.{{QbAiJob.requestedAt}}    AS requestedAt,
    j.{{QbAiJob.finishedAt}}     AS finishedAt,
    j.{{QbAiJob.expiresAt}}      AS expiresAt
FROM {{QbPublication}} p
JOIN {{QbAiJob}} j ON j.{{QbAiJob.quizId}} = p.{{QbPublication.quizId}}
WHERE p.{{QbPublication.publicationId}} = :publicationId
  AND j.{{QbAiJob.kind}} = 'TRANSLATION_CHECK'
  AND CAST(j.{{QbAiJob.params}} AS jsonb) ->> 'publicationId' = CAST(p.{{QbPublication.publicationId}} AS varchar)
  AND CAST(j.{{QbAiJob.params}} AS jsonb) ->> 'language' = CAST(:language AS varchar)
