/*---
id: qb.admin.recon-attention-days
description: >-
  F-M14 (docs/quizbuks/plans/impl/F-M14.md §3.1b, point 6): for how many days an item for attention of a reconciliation
  run has been found in a row, by check and object (the item's reference, else its party, else '-')
entities: [QbReconItem, QbReconRun]
datasets:
  QbReconItem: urn:jabiz:dataset:default:QbReconItem
  QbReconRun: urn:jabiz:dataset:default:QbReconRun
params:
  runId:  { like: QbReconItem.runId, required: true, description: Only the items of this run }
  checks: { like: QbReconItem.check, list: true, required: true, description: The checks for attention }
results:
  checkCode: { from: QbReconItem.check }
  objectKey: { kind: { type: text, maxLength: 128 } }
  days:      { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [checkCode, objectKey]
  defaultSort: { field: checkCode, asc: true }
  key: [checkCode, objectKey]
permissions: [qb.reconcile]
---*/
-- The streak of an item is the unbroken run of reconciliation runs, up to the selected one, that found the same check
-- and object: it starts at the first run after the latest earlier run that did not find it. Its days are the UTC days
-- between that first run's scheduled time and the selected run's (0 for an item found for the first time). A day
-- without a run does not break it (the reconciliation not running proves nothing about the item, as for the tasks).
-- A run that kept only part of its items (more than it keeps) can show a streak shorter than it is.
WITH sel AS (
    SELECT r.{{QbReconRun.scheduledTime}} AS s_time
    FROM {{QbReconRun}} r
    WHERE r.{{QbReconRun.runId}} = CAST(:runId AS uuid)),
cur AS (
    SELECT i.{{QbReconItem.check}} AS c_check,
           CAST(coalesce(i.{{QbReconItem.reference}}, i.{{QbReconItem.partyId}}, '-') AS varchar(128)) AS c_object
    FROM {{QbReconItem}} i
    WHERE i.{{QbReconItem.runId}} = CAST(:runId AS uuid) AND i.{{QbReconItem.check}} = ANY(:checks)
    GROUP BY 1, 2),
broken AS (
    SELECT c.c_check, c.c_object,
           (SELECT max(q.{{QbReconRun.scheduledTime}})
            FROM {{QbReconRun}} q, sel
            WHERE q.{{QbReconRun.scheduledTime}} < sel.s_time
              AND NOT EXISTS (SELECT 1 FROM {{QbReconItem}} j
                              WHERE j.{{QbReconItem.runId}} = q.{{QbReconRun.runId}}
                                AND j.{{QbReconItem.check}} = c.c_check
                                AND coalesce(j.{{QbReconItem.reference}}, j.{{QbReconItem.partyId}}, '-') = c.c_object)
           ) AS b_time
    FROM cur c)
SELECT b.c_check AS checkCode, b.c_object AS objectKey,
       CAST(CAST(sel.s_time AT TIME ZONE 'UTC' AS date) - CAST(
           (SELECT min(q.{{QbReconRun.scheduledTime}}) FROM {{QbReconRun}} q
            WHERE q.{{QbReconRun.scheduledTime}} <= sel.s_time
              AND (b.b_time IS NULL OR q.{{QbReconRun.scheduledTime}} > b.b_time)) AT TIME ZONE 'UTC' AS date)
       AS integer) AS days
FROM broken b, sel
