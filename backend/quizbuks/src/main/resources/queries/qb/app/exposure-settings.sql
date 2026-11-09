/*---
id: qb.app.exposure-settings
description: >-
  F-S13: the parameters of the exposures and the pushes the App applies: the seconds that count one, every how many
  screens of the vertical mode a push stands, how many reports it gathers and how long at most before it sends them,
  and how many one call takes. One row; a value not there yet (before the setup made it) is empty and the App takes the setup's
entities: [SysParam]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  seconds:           { kind: { type: text, maxLength: 20 } }
  pushEvery:         { kind: { type: text, maxLength: 20 } }
  reportBatch:       { kind: { type: text, maxLength: 20 } }
  reportIntervalSec: { kind: { type: text, maxLength: 20 } }
  maxPerCall:        { kind: { type: text, maxLength: 20 } }
permissions: [qb.play]
---*/
-- As text: the App bounds them as the server does (exposure.ExposureRules; exposure/rules.ts).
SELECT
    CAST(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.exposure.seconds' THEN CAST(p.{{SysParam.value}} AS text) END)
         AS varchar(20)) AS seconds,
    CAST(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.exposure.push.every' THEN CAST(p.{{SysParam.value}} AS text) END)
         AS varchar(20)) AS pushEvery,
    CAST(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.exposure.report.batch' THEN CAST(p.{{SysParam.value}} AS text) END)
         AS varchar(20)) AS reportBatch,
    CAST(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.exposure.report.interval-sec'
                  THEN CAST(p.{{SysParam.value}} AS text) END) AS varchar(20)) AS reportIntervalSec,
    CAST(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.exposure.report.max-per-call'
                  THEN CAST(p.{{SysParam.value}} AS text) END) AS varchar(20)) AS maxPerCall
FROM {{SysParam}} p
WHERE p.{{SysParam.paramKey}} IN ('qb.exposure.seconds', 'qb.exposure.push.every', 'qb.exposure.report.batch',
                                  'qb.exposure.report.interval-sec', 'qb.exposure.report.max-per-call')
