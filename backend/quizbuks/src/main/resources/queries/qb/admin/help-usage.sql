/*---
id: qb.admin.help-usage
description: >-
  F-C10: the AI help's usage by month (headquarters' AI page; apart from the Sponsors' AI usage): questions, failed and
  stopped jobs, tokens in and out (an attempt without an answer at its estimate; of the input, what the prompt cache
  wrote and read, F-C10b), a dollar estimate, how many people
  asked, and the share of qb.help.global-monthly-tokens used
entities: [QbHelpUsage, SysParam]
datasets:
  QbHelpUsage: urn:jabiz:dataset:default:QbHelpUsage
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  month: { like: QbHelpUsage.month, description: "If given, only this month (YYYY-MM, the deployment's zone)" }
results:
  month:         { from: QbHelpUsage.month }
  questions:     { from: QbHelpUsage.questions }
  failedJobs:    { from: QbHelpUsage.failedJobs }
  cancelledJobs: { from: QbHelpUsage.cancelledJobs }
  inputTokens:   { from: QbHelpUsage.inputTokens }
  outputTokens:  { from: QbHelpUsage.outputTokens }
  cacheReadTokens:  { from: QbHelpUsage.cacheReadTokens }
  cacheWriteTokens: { from: QbHelpUsage.cacheWriteTokens }
  estimatedUsd:  { kind: { type: numeric, precision: 14, scale: 2 } }
  people:        { kind: { type: numeric, precision: 9, scale: 0 } }
  monthlyTokens: { kind: { type: numeric, precision: 15, scale: 0 } }
  percentUsed:   { kind: { type: numeric, precision: 6, scale: 1 } }
list:
  filters: [month]
  sorts:   [month]
  defaultSort: { field: month, asc: false }
  key: [month]
permissions: [qb.admin.ai.read]
---*/
-- Everybody's row of each month ("*"), with the number of people who asked that month and the monthly tokens in effect
-- now (not a whole number, or none yet: its default, 20,000,000).
WITH cap AS (
    SELECT coalesce(max(CASE WHEN CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,15}$'
                             THEN CAST(CAST(p.{{SysParam.value}} AS text) AS numeric) END), 20000000) AS tokens
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.help.global-monthly-tokens')
SELECT
    u.{{QbHelpUsage.month}}         AS month,
    u.{{QbHelpUsage.questions}}     AS questions,
    u.{{QbHelpUsage.failedJobs}}    AS failedJobs,
    u.{{QbHelpUsage.cancelledJobs}} AS cancelledJobs,
    u.{{QbHelpUsage.inputTokens}}   AS inputTokens,
    u.{{QbHelpUsage.outputTokens}}  AS outputTokens,
    u.{{QbHelpUsage.cacheReadTokens}}  AS cacheReadTokens,
    u.{{QbHelpUsage.cacheWriteTokens}} AS cacheWriteTokens,
    CAST(round(u.{{QbHelpUsage.costUsd}}, 2) AS numeric(14, 2)) AS estimatedUsd,
    CAST((SELECT count(*) FROM {{QbHelpUsage}} x
          WHERE x.{{QbHelpUsage.month}} = u.{{QbHelpUsage.month}} AND x.{{QbHelpUsage.userId}} <> '*')
         AS numeric(9, 0)) AS people,
    CAST(c.tokens AS numeric(15, 0)) AS monthlyTokens,
    CAST(CASE WHEN c.tokens > 0
              THEN round((u.{{QbHelpUsage.inputTokens}} + u.{{QbHelpUsage.outputTokens}}) * 100.0 / c.tokens, 1)
         END AS numeric(6, 1)) AS percentUsed
FROM {{QbHelpUsage}} u
CROSS JOIN cap c
WHERE u.{{QbHelpUsage.userId}} = '*'
  AND (CAST(:month AS varchar) IS NULL OR u.{{QbHelpUsage.month}} = :month)
