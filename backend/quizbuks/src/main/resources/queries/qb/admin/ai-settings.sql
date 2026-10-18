/*---
id: qb.admin.ai-settings
description: >-
  The AI parameters in force (Q8a): the switch, the model and effort, the limits, the candidates' expiry and the
  prices of the estimate, each with its stored value and whether it exists yet; the controlled ones change only
  through a controlled change (four eyes)
entities: [SysParam]
results:
  paramKey:    { kind: { type: text, maxLength: 200 } }
  paramId:     { from: SysParam.paramId }
  value:       { from: SysParam.value }
  paramExists: { kind: { type: bool } }
  seq:         { kind: { type: numeric, precision: 3, scale: 0 } }
list:
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [paramKey]
permissions: [qb.admin.ai.read]
---*/
-- paramExists is false until the setup's proposal of a controlled parameter is published.
WITH wanted (k, seq) AS (
    VALUES ('qb.ai.enabled', 1), ('qb.ai.model', 2), ('qb.ai.effort', 3), ('qb.ai.sponsor.daily-jobs', 4),
           ('qb.ai.sponsor.monthly-tokens', 5), ('qb.ai.global.monthly-tokens', 6),
           ('qb.ai.candidates.expire-days', 7), ('qb.ai.price.input-per-mtok-usd', 8),
           ('qb.ai.price.output-per-mtok-usd', 9))
SELECT
    CAST(w.k AS varchar(200)) AS paramKey,
    p.{{SysParam.paramId}}    AS paramId,
    p.{{SysParam.value}}      AS value,
    p.{{SysParam.paramKey}} IS NOT NULL AS paramExists,
    w.seq AS seq
FROM wanted w
LEFT JOIN {{SysParam}} p ON p.{{SysParam.paramKey}} = w.k
