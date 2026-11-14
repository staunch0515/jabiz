/*---
id: qb.system.ai-absorbed
description: >-
  F-M9: what the platform took on of the AI fees of one fee month (absorbedYen plus the estimated dollars of calls with
  no answer in yen at each job's frozen rate), the alert in effect (qb.ai.fee.absorbed-alert-yen) and whether the
  month's task exists already (open or closed). Read by the daily check qb.ai-absorbed-check
entities: [QbAiJob, SysTask, SysParam]
datasets:
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
  SysTask: urn:jabiz:dataset:platform:SysTask
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  month: { kind: { type: text, maxLength: 7 }, required: true, description: "The fee month (YYYY-MM, the fiscal year's zone)" }
results:
  absorbedYen: { kind: { type: monetary, currency: JPY, scale: 0 } }
  alertYen:    { kind: { type: numeric, precision: 8, scale: 0 } }
  taskExists:  { kind: { type: numeric, precision: 1, scale: 0 } }
list:
  sorts: [absorbedYen]
  defaultSort: { field: absorbedYen, asc: true }
  key: [absorbedYen]
permissions: [qb.ai.system]
---*/
-- absorbedYen is the same total as absorbedTotalYen of qb.admin.ai-fees (what finance reads there): change the one with
-- the other. The alert as content.ai.AiAbsorbed reads it (not a whole number, or none yet: its default, 1,000; within 0 to 10,000,000).
-- The month's task is named qb.ai-absorbed:<month> (whether it is still open or closed by a person).
SELECT
    -- The same total as g_total of qb.admin.ai-fees (absorbed yen + the estimate's dollars at the frozen rate, rounded up).
    CAST(coalesce((SELECT sum(coalesce(j.{{QbAiJob.absorbedYen}}, 0)
                              + ceil(coalesce(j.{{QbAiJob.unrecordedUsd}}, 0) * coalesce(j.{{QbAiJob.feeUsdJpy}}, 0)))
                   FROM {{QbAiJob}} j WHERE j.{{QbAiJob.feePeriod}} = :month), 0) AS numeric(19, 0)) AS absorbedYen,
    CAST(greatest(0, least(10000000, coalesce((SELECT max(CASE WHEN CAST(p.{{SysParam.value}} AS text)
                                                                    ~ '^[0-9]{1,8}$'
                                                              THEN CAST(CAST(p.{{SysParam.value}} AS text) AS numeric) END)
                                              FROM {{SysParam}} p
                                              WHERE p.{{SysParam.paramKey}} = 'qb.ai.fee.absorbed-alert-yen'),
                                             1000))) AS numeric(8, 0)) AS alertYen,
    CAST(CASE WHEN EXISTS (SELECT 1 FROM {{SysTask}} k
                           WHERE k.{{SysTask.sourceKey}} = 'qb.ai-absorbed:' || CAST(:month AS text))
              THEN 1 ELSE 0 END AS numeric(1, 0)) AS taskExists
