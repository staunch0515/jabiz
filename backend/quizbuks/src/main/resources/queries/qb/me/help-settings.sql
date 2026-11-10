/*---
id: qb.me.help-settings
description: >-
  F-C10: what the AI help's pages need to know: whether it is on, how often and how long they ask for an answer, and the
  questions a person may ask a day, and (F-C10c) the longest question the pages take (the parameters in effect, their
  declared values before the setup)
entities: [SysParam]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  enabled:        { kind: { type: bool } }
  pollSeconds:    { kind: { type: numeric, precision: 3, scale: 0 } }
  pollMaxMinutes: { kind: { type: numeric, precision: 3, scale: 0 } }
  userDaily:      { kind: { type: numeric, precision: 5, scale: 0 } }
  questionMaxLength: { kind: { type: numeric, precision: 5, scale: 0 } }
permissions: [qb.help.use]
---*/
-- One row. The numbers as help.HelpSettings reads them: a value that is not a whole number counts as its default.
SELECT
    coalesce(bool_or(p.{{SysParam.paramKey}} = 'qb.help.enabled' AND CAST(p.{{SysParam.value}} AS text) = 'true'),
             false) AS enabled,
    CAST(greatest(1, least(600, coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.help.poll-seconds'
        AND CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,3}$' THEN CAST(CAST(p.{{SysParam.value}} AS text) AS int) END),
        3))) AS numeric(3, 0)) AS pollSeconds,
    CAST(greatest(1, least(60, coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.help.poll-max-minutes'
        AND CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,3}$' THEN CAST(CAST(p.{{SysParam.value}} AS text) AS int) END),
        3))) AS numeric(3, 0)) AS pollMaxMinutes,
    CAST(least(10000, coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.help.user-daily'
        AND CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,5}$' THEN CAST(CAST(p.{{SysParam.value}} AS text) AS int) END),
        20)) AS numeric(5, 0)) AS userDaily,
    CAST(greatest(100, least(4000, coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.help.question-max-length'
        AND CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,5}$' THEN CAST(CAST(p.{{SysParam.value}} AS text) AS int) END),
        2000))) AS numeric(5, 0)) AS questionMaxLength
FROM {{SysParam}} p
WHERE p.{{SysParam.paramKey}} IN ('qb.help.enabled', 'qb.help.poll-seconds', 'qb.help.poll-max-minutes',
                                  'qb.help.user-daily', 'qb.help.question-max-length')
