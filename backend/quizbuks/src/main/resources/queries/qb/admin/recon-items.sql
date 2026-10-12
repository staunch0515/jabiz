/*---
id: qb.admin.recon-items
description: The differences a reconciliation run found (F-06) - what the truth says, what was found, and by how much they differ
entities: [QbReconItem, QbReconRun]
datasets:
  QbReconItem: urn:jabiz:dataset:default:QbReconItem
  QbReconRun: urn:jabiz:dataset:default:QbReconRun
params:
  runId: { like: QbReconItem.runId, description: Only the differences of this run }
results:
  itemId:        { from: QbReconItem.itemId }
  runId:         { from: QbReconItem.runId }
  scheduledTime: { from: QbReconRun.scheduledTime }
  checkCode:     { from: QbReconItem.check }
  partyId:       { from: QbReconItem.partyId }
  reference:     { from: QbReconItem.reference }
  expected:      { from: QbReconItem.expected }
  actual:        { from: QbReconItem.actual }
  difference:    { from: QbReconItem.difference }
list:
  filters: [checkCode, partyId, reference, scheduledTime]
  sorts:   [scheduledTime, checkCode, difference]
  defaultSort: { field: scheduledTime, asc: false }
  key: [itemId]
permissions: [qb.reconcile]
---*/
SELECT
    i.{{QbReconItem.itemId}}       AS itemId,
    i.{{QbReconItem.runId}}        AS runId,
    r.{{QbReconRun.scheduledTime}} AS scheduledTime,
    i.{{QbReconItem.check}}        AS checkCode,
    i.{{QbReconItem.partyId}}      AS partyId,
    i.{{QbReconItem.reference}}    AS reference,
    i.{{QbReconItem.expected}}     AS expected,
    i.{{QbReconItem.actual}}       AS actual,
    i.{{QbReconItem.difference}}   AS difference
FROM {{QbReconItem}} i
JOIN {{QbReconRun}} r ON r.{{QbReconRun.runId}} = i.{{QbReconItem.runId}}
WHERE (CAST(:runId AS uuid) IS NULL OR i.{{QbReconItem.runId}} = CAST(:runId AS uuid))
