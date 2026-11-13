/*---
id: qb.system.operator-settlement-alert
description: "The source key of a finance task about a capped operator settlement, when no task of that source exists yet (F-M12 b)"
entities: [SysTask]
datasets:
  SysTask: urn:jabiz:dataset:platform:SysTask
params:
  sourceKey: { kind: { type: text, maxLength: 120 }, required: true, description: "The task's source key" }
results:
  sourceKey: { kind: { type: text, maxLength: 120 } }
list:
  sorts: [sourceKey]
  defaultSort: { field: sourceKey, asc: true }
  key: [sourceKey]
permissions: [qb.operator.settlement.system]
---*/
-- The platform's CreateTask does not deduplicate: a task of this source, open or closed, means finance was asked already.
-- (The settlement of an operator's month is written once, so this is the second guard; it runs only when a task is due.)
SELECT CAST(:sourceKey AS text) AS sourceKey
WHERE NOT EXISTS (SELECT 1 FROM {{SysTask}} t WHERE t.{{SysTask.sourceKey}} = CAST(:sourceKey AS text))
