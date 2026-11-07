/*---
id: qb.admin.prospect-switch
description: >-
  AI prospecting's platform switch in force (F-F8, troubleshooting T9): qb.prospect.enabled, model and effort, each with
  its stored value and whether it exists yet; they change only through a controlled change (four eyes), proposed from
  the prospecting settings page
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
permissions: [qb.admin.prospect]
---*/
-- paramExists is false until the setup's proposal of a controlled parameter is published.
WITH wanted (k, seq) AS (
    VALUES ('qb.prospect.enabled', 1), ('qb.prospect.model', 2), ('qb.prospect.effort', 3))
SELECT
    CAST(w.k AS varchar(200)) AS paramKey,
    p.{{SysParam.paramId}}    AS paramId,
    p.{{SysParam.value}}      AS value,
    p.{{SysParam.paramKey}} IS NOT NULL AS paramExists,
    w.seq AS seq
FROM wanted w
LEFT JOIN {{SysParam}} p ON p.{{SysParam.paramKey}} = w.k
