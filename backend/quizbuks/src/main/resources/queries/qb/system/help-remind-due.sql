/*---
id: qb.system.help-remind-due
description: >-
  F-C10: the AI help conversations waiting for a person longer than qb.help.escalation-remind-days days whose wait
  reminded nobody yet (the items of the job qb.help-remind; QB_HELP_REMIND reads its conversation again under its lock)
entities: [QbHelpThread, SysTask, SysParam]
datasets:
  QbHelpThread: urn:jabiz:dataset:default:QbHelpThread
  SysTask: urn:jabiz:dataset:platform:SysTask
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at:       { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time of the run }
  threadId: { kind: { type: text, maxLength: 36 }, description: "Only this conversation (the item's check under its lock)" }
results:
  threadId:     { from: QbHelpThread.threadId }
  waitingSince: { from: QbHelpThread.waitingSince }
  days:         { kind: { type: numeric, precision: 4, scale: 0 } }
list:
  sorts: [waitingSince]
  defaultSort: { field: waitingSince, asc: true }
  key: [threadId]
permissions: [qb.help.system]
---*/
-- The days as help.HelpSettings reads them (not a whole number, or none yet: 2; within 1 to 3650). Once per wait: its
-- reminder's task is named by when the wait began (QbHelp.remindSource), open or closed.
WITH params AS (
    SELECT greatest(1, least(3650, coalesce(max(CASE WHEN CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,4}$'
                                                     THEN CAST(CAST(p.{{SysParam.value}} AS text) AS int) END),
                                            2))) AS days
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.help.escalation-remind-days')
SELECT t.{{QbHelpThread.threadId}} AS threadId, t.{{QbHelpThread.waitingSince}} AS waitingSince,
       CAST(p.days AS numeric(4, 0)) AS days
FROM {{QbHelpThread}} t
CROSS JOIN params p
WHERE t.{{QbHelpThread.status}} = 'ESCALATED'
  AND t.{{QbHelpThread.waitingFor}} = 'STAFF'
  AND t.{{QbHelpThread.waitingSince}} <= CAST(:at AS timestamptz) - p.days * interval '1 day'
  AND (CAST(:threadId AS text) IS NULL OR CAST(t.{{QbHelpThread.threadId}} AS text) = CAST(:threadId AS text))
  AND NOT EXISTS (SELECT 1 FROM {{SysTask}} k
                  WHERE k.{{SysTask.sourceKey}} = 'qb.help-remind:' || CAST(t.{{QbHelpThread.threadId}} AS text) || ':'
                        || CAST(floor(extract(epoch FROM t.{{QbHelpThread.waitingSince}})) AS bigint))
