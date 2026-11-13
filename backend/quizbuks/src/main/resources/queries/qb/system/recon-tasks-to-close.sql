/*---
id: qb.system.recon-tasks-to-close
description: >-
  F-M14 (docs/quizbuks/plans/impl/F-M14.md §3.1b): the reconciliation's open tasks that the latest run within a day
  before :at (if no later one exists) settles (the items of the job qb.recon-tasks-close; none when the reconciliation did not run). A
  difference's task is done once a run has no difference, and cancelled when a later day's replaces it; a task for
  attention is done once its check and object are no longer found, unless the run kept only part of its items.
entities: [QbReconRun, QbReconItem, SysTask]
datasets:
  QbReconRun: urn:jabiz:dataset:default:QbReconRun
  QbReconItem: urn:jabiz:dataset:default:QbReconItem
  SysTask: urn:jabiz:dataset:platform:SysTask
params:
  at: { like: QbReconRun.scheduledTime, required: true, description: "The job's scheduled time" }
results:
  itemKey:   { kind: { type: text, maxLength: 32 } }
  sourceKey: { kind: { type: text, maxLength: 200 } }
  outcome:   { kind: { type: text, maxLength: 10 } }
list:
  sorts: [sourceKey]
  defaultSort: { field: sourceKey, asc: true }
  key: [itemKey]
permissions: [qb.wallet.system]
---*/
WITH latest AS (
    SELECT r.{{QbReconRun.runId}} AS r_run, r.{{QbReconRun.status}} AS r_status,
           r.{{QbReconRun.itemCount}} AS r_count,
           'recon:' || to_char(r.{{QbReconRun.scheduledTime}} AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS r_day
    FROM {{QbReconRun}} r
    WHERE r.{{QbReconRun.scheduledTime}} <= CAST(:at AS timestamptz)
      AND r.{{QbReconRun.scheduledTime}} > CAST(:at AS timestamptz) - interval '1 day'
      -- A run of an earlier time made up later (JOB_RUN_REQUEST): nothing, while a later run stands.
      AND NOT EXISTS (SELECT 1 FROM {{QbReconRun}} n
                      WHERE n.{{QbReconRun.scheduledTime}} > r.{{QbReconRun.scheduledTime}})
    ORDER BY r.{{QbReconRun.scheduledTime}} DESC
    LIMIT 1),
kept AS (
    SELECT count(*) AS n FROM {{QbReconItem}} i JOIN latest l ON i.{{QbReconItem.runId}} = l.r_run),
open_tasks AS (
    SELECT DISTINCT t.{{SysTask.sourceKey}} AS r_key, t.{{SysTask.taskType}} AS r_type
    FROM {{SysTask}} t
    WHERE t.{{SysTask.status}} = 'OPEN'
      AND t.{{SysTask.taskType}} IN ('qb.recon-diff', 'qb.recon-attention')
      AND t.{{SysTask.sourceKey}} IS NOT NULL)
SELECT CAST(md5(o.r_key) AS varchar(32)) AS itemKey, CAST(o.r_key AS varchar(200)) AS sourceKey,
       CAST(CASE WHEN o.r_type = 'qb.recon-diff' AND l.r_status = 'OK' THEN 'DONE'
                 WHEN o.r_type = 'qb.recon-diff' THEN 'CANCELLED'
                 ELSE 'DONE' END AS varchar(10)) AS outcome
FROM open_tasks o
CROSS JOIN latest l
CROSS JOIN kept k
WHERE (o.r_type = 'qb.recon-diff' AND (l.r_status = 'OK' OR o.r_key <> l.r_day))
   OR (o.r_type = 'qb.recon-attention'
       AND k.n >= l.r_count
       AND NOT EXISTS (SELECT 1 FROM {{QbReconItem}} i
                       WHERE i.{{QbReconItem.runId}} = l.r_run
                         AND CAST('recon-attn:' || i.{{QbReconItem.check}} || ':'
                                  || coalesce(i.{{QbReconItem.reference}}, i.{{QbReconItem.partyId}}, '-') AS varchar(200))
                             = o.r_key))
