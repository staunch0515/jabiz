/*---
id: qb.system.help-cost
description: >-
  F-M10: the estimated dollars the platform paid for AI in one month, the AI help (everybody's row of the month) and
  the reviewers' translation checks (the jobs of kind TRANSLATION_CHECK finished in the month) apart, the dollar alert
  now in effect, and whether the month's task exists already (open or closed). Read by the help's job outcomes (the
  gauge, also from the completion of a translation check) and the daily check qb.help-usage (the task)
entities: [QbHelpUsage, QbAiJob, SysTask, SysParam]
datasets:
  QbHelpUsage: urn:jabiz:dataset:default:QbHelpUsage
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
  SysTask: urn:jabiz:dataset:platform:SysTask
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  month: { kind: { type: text, maxLength: 7 }, required: true, description: "The month (YYYY-MM, the deployment's zone)" }
  from:  { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The start of the month }
  to:    { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The start of the next month }
results:
  usageUsd:  { kind: { type: numeric, precision: 16, scale: 6 } }
  checksUsd: { kind: { type: numeric, precision: 16, scale: 6 } }
  alertUsd:  { kind: { type: numeric, precision: 9, scale: 2 } }
  taskExists: { kind: { type: numeric, precision: 1, scale: 0 } }
list:
  sorts: [usageUsd]
  defaultSort: { field: usageUsd, asc: true }
  key: [usageUsd]
permissions: [qb.help.system]
---*/
-- The alert as help.HelpSettings reads it (not a number, or none yet: its default, 8; within 0 to 1,000,000). The
-- month's task is named qb.help-cost:<month> (help.HelpUpkeep), whether it is still open or closed by a person.
SELECT
    CAST(coalesce((SELECT sum(u.{{QbHelpUsage.costUsd}}) FROM {{QbHelpUsage}} u
                   WHERE u.{{QbHelpUsage.userId}} = '*' AND u.{{QbHelpUsage.month}} = :month), 0)
         AS numeric(16, 6)) AS usageUsd,
    CAST(coalesce((SELECT sum(j.{{QbAiJob.costUsd}}) FROM {{QbAiJob}} j
                   WHERE j.{{QbAiJob.kind}} = 'TRANSLATION_CHECK'
                     AND j.{{QbAiJob.finishedAt}} >= CAST(:from AS timestamptz)
                     AND j.{{QbAiJob.finishedAt}} < CAST(:to AS timestamptz)), 0)
         AS numeric(16, 6)) AS checksUsd,
    CAST(greatest(0, least(1000000, coalesce((SELECT max(CASE WHEN CAST(p.{{SysParam.value}} AS text)
                                                                    ~ '^[0-9]{1,7}(\.[0-9]{1,2})?$'
                                                              THEN CAST(CAST(p.{{SysParam.value}} AS text) AS numeric) END)
                                              FROM {{SysParam}} p
                                              WHERE p.{{SysParam.paramKey}} = 'qb.help.global-monthly-usd-alert'),
                                             8))) AS numeric(9, 2)) AS alertUsd,
    CAST(CASE WHEN EXISTS (SELECT 1 FROM {{SysTask}} k
                           WHERE k.{{SysTask.sourceKey}} = 'qb.help-cost:' || CAST(:month AS text))
              THEN 1 ELSE 0 END AS numeric(1, 0)) AS taskExists
