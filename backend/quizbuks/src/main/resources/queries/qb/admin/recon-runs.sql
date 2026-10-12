/*---
id: qb.admin.recon-runs
description: The daily reconciliation's runs (F-06), newest first, with their outcome and the count of differences
entities: [QbReconRun]
datasets:
  QbReconRun: urn:jabiz:dataset:default:QbReconRun
results:
  runId:         { from: QbReconRun.runId }
  scheduledTime: { from: QbReconRun.scheduledTime }
  status:        { from: QbReconRun.status }
  itemCount:     { from: QbReconRun.itemCount }
  checkedAt:     { from: QbReconRun.checkedAt }
  summary:       { from: QbReconRun.summary }
list:
  filters: [status, scheduledTime]
  sorts:   [scheduledTime]
  defaultSort: { field: scheduledTime, asc: false }
  key: [runId]
permissions: [qb.reconcile]
---*/
SELECT
    r.{{QbReconRun.runId}}         AS runId,
    r.{{QbReconRun.scheduledTime}} AS scheduledTime,
    r.{{QbReconRun.status}}        AS status,
    r.{{QbReconRun.itemCount}}     AS itemCount,
    r.{{QbReconRun.checkedAt}}     AS checkedAt,
    r.{{QbReconRun.summary}}       AS summary
FROM {{QbReconRun}} r
