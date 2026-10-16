/*---
id: qb.admin.recon-summary
description: >-
  The latest daily reconciliation and the open money tasks (Q9c), for finance's overview; one row
entities: [QbReconRun, SysTask]
datasets:
  QbReconRun: urn:jabiz:dataset:default:QbReconRun
  SysTask: urn:jabiz:dataset:platform:SysTask
results:
  lastRunId:     { from: QbReconRun.runId }
  lastRunAt:     { from: QbReconRun.scheduledTime }
  lastRunStatus: { from: QbReconRun.status }
  lastRunItems:  { from: QbReconRun.itemCount }
  openTasks:     { kind: { type: numeric, precision: 12, scale: 0 } }
permissions: [qb.reconcile, task.read]
---*/
-- Apart from qb.admin.finance-summary (qb.admin.finance.read): the reconciliation's results are qb.reconcile's, as in
-- qb.admin.recon-runs, and the tasks are the platform's (task.read). The money tasks are those QuizBuks assigns to
-- qb.reconcile (top-ups, refunds, payout accounts, transfers, reconciliation differences): whoever holds both reads
-- them in the platform's task list too.
SELECT
    r.last_id     AS lastRunId,
    r.last_at     AS lastRunAt,
    r.last_status AS lastRunStatus,
    r.last_items  AS lastRunItems,
    k.open_tasks  AS openTasks
FROM (
    SELECT count(*) AS open_tasks
    FROM {{SysTask}} s
    WHERE s.{{SysTask.assigneePermission}} = 'qb.reconcile' AND s.{{SysTask.status}} = 'OPEN') k
LEFT JOIN (
    SELECT q.{{QbReconRun.runId}} AS last_id, q.{{QbReconRun.scheduledTime}} AS last_at,
           q.{{QbReconRun.status}} AS last_status, q.{{QbReconRun.itemCount}} AS last_items
    FROM {{QbReconRun}} q
    ORDER BY q.{{QbReconRun.scheduledTime}} DESC
    LIMIT 1) r ON true
