/*---
id: qb.system.video-use-reminders-due
description: >-
  F-S14: the shared videos' uses waiting for their provider longer than qb.video-use.remind-days days whose submitted
  revision reminded nobody yet (the items of the job qb.video-use-remind; QB_VIDEO_USE_REMIND reads its use again
  under its lock)
entities: [QbVideoUse, SysTask, SysParam]
datasets:
  QbVideoUse: urn:jabiz:dataset:default:QbVideoUse
  SysTask: urn:jabiz:dataset:platform:SysTask
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at:         { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time of the run }
  videoUseId: { kind: { type: text, maxLength: 36 }, description: "Only this use (the reminder's check under its lock)" }
results:
  videoUseId:  { from: QbVideoUse.videoUseId }
  revision:    { from: QbVideoUse.revision }
  sponsorId:   { from: QbVideoUse.sponsorId }
  providerId:  { from: QbVideoUse.providerId }
  submittedAt: { from: QbVideoUse.submittedAt }
  days:        { kind: { type: numeric, precision: 2, scale: 0 } }
list:
  sorts: [submittedAt]
  defaultSort: { field: submittedAt, asc: true }
  key: [videoUseId]
permissions: [qb.publication.system]
---*/
-- The days as videouse.VideoUseRules.remindDays reads them (not a whole number, or none yet: 7; within 1 to 90). Once
-- per submitted revision: its reminders' tasks are named by it (VideoUseRules.reminderSource), open or closed.
WITH params AS (
    SELECT greatest(1, least(90, coalesce(max(CASE WHEN CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,2}$'
                                                   THEN CAST(CAST(p.{{SysParam.value}} AS text) AS int) END), 7))) AS days
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.video-use.remind-days')
SELECT u.{{QbVideoUse.videoUseId}} AS videoUseId, u.{{QbVideoUse.revision}} AS revision,
       u.{{QbVideoUse.sponsorId}} AS sponsorId, u.{{QbVideoUse.providerId}} AS providerId,
       u.{{QbVideoUse.submittedAt}} AS submittedAt, p.days AS days
FROM {{QbVideoUse}} u
CROSS JOIN params p
WHERE u.{{QbVideoUse.status}} = 'SUBMITTED'
  AND u.{{QbVideoUse.submittedAt}} <= CAST(:at AS timestamptz) - p.days * interval '1 day'
  AND (CAST(:videoUseId AS text) IS NULL OR CAST(u.{{QbVideoUse.videoUseId}} AS text) = CAST(:videoUseId AS text))
  AND NOT EXISTS (SELECT 1 FROM {{SysTask}} t
                  WHERE t.{{SysTask.sourceKey}} = 'qb.video-use-remind:' || CAST(u.{{QbVideoUse.videoUseId}} AS text)
                                                  || ':' || CAST(u.{{QbVideoUse.revision}} AS text))
