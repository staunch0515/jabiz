/*---
id: qb.system.help-retention-due
description: >-
  F-C10: the AI help conversations past qb.help.retention-days days since their last activity (the items of the job
  qb.help-retention; QB_HELP_RETENTION reads its conversation again under its lock)
entities: [QbHelpThread, SysParam]
datasets:
  QbHelpThread: urn:jabiz:dataset:default:QbHelpThread
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at:       { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time of the run }
  threadId: { kind: { type: text, maxLength: 36 }, description: "Only this conversation (the item's check under its lock)" }
results:
  threadId:       { from: QbHelpThread.threadId }
  lastActivityAt: { from: QbHelpThread.lastActivityAt }
list:
  sorts: [lastActivityAt]
  defaultSort: { field: lastActivityAt, asc: true }
  key: [threadId]
permissions: [qb.help.system]
---*/
-- The days as help.HelpSettings reads them (not a whole number, or none yet: 180; within 1 to 3650).
WITH params AS (
    SELECT greatest(1, least(3650, coalesce(max(CASE WHEN CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,4}$'
                                                     THEN CAST(CAST(p.{{SysParam.value}} AS text) AS int) END),
                                            180))) AS days
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.help.retention-days')
SELECT t.{{QbHelpThread.threadId}} AS threadId, t.{{QbHelpThread.lastActivityAt}} AS lastActivityAt
FROM {{QbHelpThread}} t
CROSS JOIN params p
WHERE t.{{QbHelpThread.lastActivityAt}} <= CAST(:at AS timestamptz) - p.days * interval '1 day'
  AND (CAST(:threadId AS text) IS NULL OR CAST(t.{{QbHelpThread.threadId}} AS text) = CAST(:threadId AS text))
