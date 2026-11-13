/*---
id: qb.system.recon-attention-due
description: >-
  F-M14 (docs/quizbuks/plans/impl/F-M14.md §3.1b): the items for attention of the latest reconciliation within a day
  before :at, one per check and object, that have no open task of their own (the items of the job qb.recon-attention;
  none when the reconciliation did not run)
entities: [QbReconRun, QbReconItem, SysTask]
datasets:
  QbReconRun: urn:jabiz:dataset:default:QbReconRun
  QbReconItem: urn:jabiz:dataset:default:QbReconItem
  SysTask: urn:jabiz:dataset:platform:SysTask
params:
  at:     { like: QbReconRun.scheduledTime, required: true, description: "The job's scheduled time" }
  checks: { like: QbReconItem.check, list: true, required: true, description: "The checks for attention" }
results:
  itemKey:   { kind: { type: text, maxLength: 32 } }
  sourceKey: { kind: { type: text, maxLength: 200 } }
  checkCode: { from: QbReconItem.check }
  partyId:   { from: QbReconItem.partyId }
  reference: { from: QbReconItem.reference }
  expected:  { from: QbReconItem.expected }
  actual:    { from: QbReconItem.actual }
list:
  sorts: [sourceKey]
  defaultSort: { field: sourceKey, asc: true }
  key: [itemKey]
permissions: [qb.wallet.system]
---*/
-- The object is the item's reference, else its party, else '-' (ReconTasks.sourceKey). Only an open task holds a new
-- one back: one closed by hand while the item is still found is asked for again.
WITH latest AS (
    SELECT r.{{QbReconRun.runId}} AS r_run
    FROM {{QbReconRun}} r
    WHERE r.{{QbReconRun.scheduledTime}} <= CAST(:at AS timestamptz)
      AND r.{{QbReconRun.scheduledTime}} > CAST(:at AS timestamptz) - interval '1 day'
    ORDER BY r.{{QbReconRun.scheduledTime}} DESC
    LIMIT 1),
found AS (
    SELECT DISTINCT ON (k.r_key) k.r_key, k.r_check, k.r_party, k.r_reference, k.r_expected, k.r_actual
    FROM (SELECT CAST('recon-attn:' || i.{{QbReconItem.check}} || ':'
                      || coalesce(i.{{QbReconItem.reference}}, i.{{QbReconItem.partyId}}, '-') AS varchar(200)) AS r_key,
                 i.{{QbReconItem.check}} AS r_check, i.{{QbReconItem.partyId}} AS r_party,
                 i.{{QbReconItem.reference}} AS r_reference, i.{{QbReconItem.expected}} AS r_expected,
                 i.{{QbReconItem.actual}} AS r_actual
          FROM {{QbReconItem}} i
          JOIN latest l ON i.{{QbReconItem.runId}} = l.r_run
          WHERE i.{{QbReconItem.check}} = ANY(:checks)) k
    ORDER BY k.r_key)
-- The job's item key: the source key is longer than an item key may be.
SELECT CAST(md5(f.r_key) AS varchar(32)) AS itemKey, f.r_key AS sourceKey, f.r_check AS checkCode, f.r_party AS partyId, f.r_reference AS reference,
       f.r_expected AS expected, f.r_actual AS actual
FROM found f
WHERE NOT EXISTS (SELECT 1 FROM {{SysTask}} t
                  WHERE t.{{SysTask.sourceKey}} = f.r_key AND t.{{SysTask.status}} = 'OPEN')
