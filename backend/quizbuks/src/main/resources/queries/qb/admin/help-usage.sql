/*---
id: qb.admin.help-usage
description: >-
  F-C10: the AI help's usage by month (headquarters' AI page; apart from the Sponsors' AI usage): questions, failed and
  stopped jobs, tokens in and out (an attempt without an answer at its estimate; of the input, what the prompt cache
  wrote and read, F-C10b), a dollar estimate, how many people
  asked, and the share of qb.help.global-monthly-tokens used (cache reads weighted by qb.help.cache-read-weight).
  F-M10: also the reviewers' translation checks of the month (the platform pays for them too; their jobs finished in the
  month, in the deployment's zone), the dollar alert qb.help.global-monthly-usd-alert and the help's and the checks'
  dollars together as a share of it
entities: [QbHelpUsage, QbAiJob, SysParam]
datasets:
  QbHelpUsage: urn:jabiz:dataset:default:QbHelpUsage
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
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
  checkJobs:     { kind: { type: numeric, precision: 9, scale: 0 } }
  checksUsd:     { kind: { type: numeric, precision: 14, scale: 2 } }
  totalUsd:      { kind: { type: numeric, precision: 14, scale: 2 } }
  alertUsd:      { kind: { type: numeric, precision: 9, scale: 2 } }
  percentOfAlert: { kind: { type: numeric, precision: 7, scale: 1 } }
  reachedAlert:  { kind: { type: numeric, precision: 1, scale: 0 } }
list:
  filters: [month]
  sorts:   [month]
  defaultSort: { field: month, asc: false }
  key: [month]
permissions: [qb.admin.ai.read]
---*/
-- Everybody's row of each month ("*"), with the number of people who asked that month and the monthly tokens in effect
-- now (not a whole number, or none yet: its default, 20,000,000).
-- F-M10: the alert as help.HelpSettings reads it (not a number, or none yet: 8; within 0 to 1,000,000). The checks are
-- the jobs of kind TRANSLATION_CHECK by the month they finished in, in the zone the job recorded (the deployment's
-- fiscal-year zone when it ran, as the usage months are); the percent is empty with no alert (0), not 0, and
-- reachedAlert compares the unrounded dollars. A month with checks but no help usage is not listed here (a known
-- limit: the alert and the task count it all the same).
-- F-C10b: an input token read from the prompt cache counts qb.help.cache-read-weight (0 to 1; none yet or not a
-- number: its default, 0.1), rounded up, as the processes count it.
WITH cap AS (
    SELECT coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.help.global-monthly-tokens'
                              AND CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,15}$'
                             THEN CAST(CAST(p.{{SysParam.value}} AS text) AS numeric) END), 20000000) AS tokens,
           least(1, greatest(0, coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.help.cache-read-weight'
                              AND CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]+(\.[0-9]+)?$'
                             THEN CAST(CAST(p.{{SysParam.value}} AS text) AS numeric) END), 0.1))) AS weight
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.help.global-monthly-tokens', 'qb.help.cache-read-weight')),
alert AS (
    SELECT greatest(0, least(1000000, coalesce(max(CASE WHEN CAST(p.{{SysParam.value}} AS text)
                                                             ~ '^[0-9]{1,7}(\.[0-9]{1,2})?$'
                                                       THEN CAST(CAST(p.{{SysParam.value}} AS text) AS numeric) END),
                                                8))) AS usd
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.help.global-monthly-usd-alert'),
checks AS (
    SELECT to_char(j.{{QbAiJob.finishedAt}} AT TIME ZONE j.{{QbAiJob.zone}}, 'YYYY-MM') AS month,
           count(*) AS jobs, coalesce(sum(j.{{QbAiJob.costUsd}}), 0) AS usd
    FROM {{QbAiJob}} j
    WHERE j.{{QbAiJob.kind}} = 'TRANSLATION_CHECK' AND j.{{QbAiJob.finishedAt}} IS NOT NULL
    GROUP BY 1)
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
              THEN round((u.{{QbHelpUsage.inputTokens}} - u.{{QbHelpUsage.cacheReadTokens}}
                          + ceil(u.{{QbHelpUsage.cacheReadTokens}} * c.weight)
                          + u.{{QbHelpUsage.outputTokens}}) * 100.0 / c.tokens, 1)
         END AS numeric(6, 1)) AS percentUsed,
    CAST(coalesce(k.jobs, 0) AS numeric(9, 0)) AS checkJobs,
    CAST(round(coalesce(k.usd, 0), 2) AS numeric(14, 2)) AS checksUsd,
    CAST(round(coalesce(u.{{QbHelpUsage.costUsd}}, 0) + coalesce(k.usd, 0), 2) AS numeric(14, 2)) AS totalUsd,
    CAST(a.usd AS numeric(9, 2)) AS alertUsd,
    CAST(CASE WHEN a.usd > 0
              THEN round((coalesce(u.{{QbHelpUsage.costUsd}}, 0) + coalesce(k.usd, 0)) * 100.0 / a.usd, 1)
         END AS numeric(7, 1)) AS percentOfAlert,
    CAST(CASE WHEN a.usd > 0 AND coalesce(u.{{QbHelpUsage.costUsd}}, 0) + coalesce(k.usd, 0) >= a.usd
              THEN 1 ELSE 0 END AS numeric(1, 0)) AS reachedAlert
FROM {{QbHelpUsage}} u
CROSS JOIN cap c
CROSS JOIN alert a
LEFT JOIN checks k ON k.month = u.{{QbHelpUsage.month}}
WHERE u.{{QbHelpUsage.userId}} = '*'
  AND (CAST(:month AS varchar) IS NULL OR u.{{QbHelpUsage.month}} = :month)
