/*---
id: qb.admin.ai-usage
description: >-
  AI usage by sponsor and month (Q8a, M-10): jobs, failed jobs, tokens in and out, and a dollar estimate with each
  token priced at the model that used it (for reference only: nothing is billed, A-8); the row of everybody's is the
  sponsor "*"
entities: [QbAiUsage, QbSponsor]
datasets:
  QbAiUsage: urn:jabiz:dataset:default:QbAiUsage
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  month: { like: QbAiUsage.month, description: "If given, only this month (YYYY-MM, the deployment's zone)" }
results:
  usageId:      { from: QbAiUsage.usageId }
  sponsorId:    { from: QbAiUsage.sponsorId }
  company:      { from: QbSponsor.company }
  month:        { from: QbAiUsage.month }
  jobs:         { from: QbAiUsage.jobs }
  failedJobs:   { from: QbAiUsage.failedJobs }
  inputTokens:  { from: QbAiUsage.inputTokens }
  outputTokens: { from: QbAiUsage.outputTokens }
  estimatedUsd: { kind: { type: numeric, precision: 14, scale: 2 } }
list:
  filters: [sponsorId, month]
  sorts:   [month, inputTokens, outputTokens, jobs]
  defaultSort: { field: month, asc: false }
  key: [usageId]
permissions: [qb.admin.ai.read]
---*/
-- The estimate is what the finished jobs added up (content.ai.AiCost, at their completion): the requested model's
-- tokens at its price, the provider's fallback model's at that model's, a model QuizBuks does not know at the price
-- parameters (qb.ai.price.*). An attempt that got no answer is in it at its estimate, as the limits count it.
SELECT
    u.{{QbAiUsage.usageId}}      AS usageId,
    u.{{QbAiUsage.sponsorId}}    AS sponsorId,
    s.{{QbSponsor.company}}      AS company,
    u.{{QbAiUsage.month}}        AS month,
    u.{{QbAiUsage.jobs}}         AS jobs,
    u.{{QbAiUsage.failedJobs}}   AS failedJobs,
    u.{{QbAiUsage.inputTokens}}  AS inputTokens,
    u.{{QbAiUsage.outputTokens}} AS outputTokens,
    CAST(round(u.{{QbAiUsage.costUsd}}, 2) AS numeric(14, 2)) AS estimatedUsd
FROM {{QbAiUsage}} u
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = u.{{QbAiUsage.sponsorId}}
WHERE (CAST(:month AS varchar) IS NULL OR u.{{QbAiUsage.month}} = :month)
