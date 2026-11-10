/*---
id: qb.system.help-auto-close-due
description: >-
  F-C10: the AI help conversations handed to a person whose reply the user left unanswered for qb.help.auto-close-days
  days (the items of the job qb.help-auto-close; QB_HELP_AUTO_CLOSE reads its conversation again under its lock)
entities: [QbHelpThread, SysParam]
datasets:
  QbHelpThread: urn:jabiz:dataset:default:QbHelpThread
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at:       { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time of the run }
  threadId: { kind: { type: text, maxLength: 36 }, description: "Only this conversation (the item's check under its lock)" }
results:
  threadId:     { from: QbHelpThread.threadId }
  waitingSince: { from: QbHelpThread.waitingSince }
list:
  sorts: [waitingSince]
  defaultSort: { field: waitingSince, asc: true }
  key: [threadId]
permissions: [qb.help.system]
---*/
-- The days as help.HelpSettings reads them (not a whole number, or none yet: 14; within 1 to 3650). A person replied
-- (waiting for the user) and the user wrote nothing since.
WITH params AS (
    SELECT greatest(1, least(3650, coalesce(max(CASE WHEN CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,4}$'
                                                     THEN CAST(CAST(p.{{SysParam.value}} AS text) AS int) END),
                                            14))) AS days
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.help.auto-close-days')
SELECT t.{{QbHelpThread.threadId}} AS threadId, t.{{QbHelpThread.waitingSince}} AS waitingSince
FROM {{QbHelpThread}} t
CROSS JOIN params p
WHERE t.{{QbHelpThread.status}} = 'ESCALATED'
  AND t.{{QbHelpThread.waitingFor}} = 'USER'
  AND t.{{QbHelpThread.waitingSince}} <= CAST(:at AS timestamptz) - p.days * interval '1 day'
  AND (CAST(:threadId AS text) IS NULL OR CAST(t.{{QbHelpThread.threadId}} AS text) = CAST(:threadId AS text))
