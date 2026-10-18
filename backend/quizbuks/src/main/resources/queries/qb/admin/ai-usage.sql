/*---
id: qb.admin.ai-usage
description: >-
  AI usage by sponsor and month (Q8a, M-10): jobs, failed jobs, tokens in and out, and a dollar estimate at the price
  parameters (for reference only: nothing is billed, A-8); the row of everybody's is the sponsor "*"
entities: [QbAiUsage, QbSponsor, SysParam]
datasets:
  QbAiUsage: urn:jabiz:dataset:default:QbAiUsage
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  SysParam: urn:jabiz:dataset:now:SysParam
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
-- The estimate is tokens / 10^6 times the price parameters (qb.ai.price.*); null while either is not a number.
WITH price AS (
    SELECT
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.ai.price.input-per-mtok-usd' THEN p.{{SysParam.value}} END)
            AS p_in,
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.ai.price.output-per-mtok-usd' THEN p.{{SysParam.value}} END)
            AS p_out
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.ai.price.input-per-mtok-usd', 'qb.ai.price.output-per-mtok-usd')),
checked AS (
    SELECT
        CASE WHEN p_in ~ '^[0-9]{1,4}(\.[0-9]{1,4})?$' THEN CAST(p_in AS numeric) END AS v_in,
        CASE WHEN p_out ~ '^[0-9]{1,4}(\.[0-9]{1,4})?$' THEN CAST(p_out AS numeric) END AS v_out
    FROM price)
SELECT
    u.{{QbAiUsage.usageId}}      AS usageId,
    u.{{QbAiUsage.sponsorId}}    AS sponsorId,
    s.{{QbSponsor.company}}      AS company,
    u.{{QbAiUsage.month}}        AS month,
    u.{{QbAiUsage.jobs}}         AS jobs,
    u.{{QbAiUsage.failedJobs}}   AS failedJobs,
    u.{{QbAiUsage.inputTokens}}  AS inputTokens,
    u.{{QbAiUsage.outputTokens}} AS outputTokens,
    round((u.{{QbAiUsage.inputTokens}} * c.v_in + u.{{QbAiUsage.outputTokens}} * c.v_out) / 1000000, 2)
        AS estimatedUsd
FROM {{QbAiUsage}} u
CROSS JOIN checked c
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = u.{{QbAiUsage.sponsorId}}
WHERE (CAST(:month AS varchar) IS NULL OR u.{{QbAiUsage.month}} = :month)
