/*---
id: qb.admin.reward-defaults
description: >-
  The reward parameters in force (Q28): the methods a sponsor without a setting of their own may publish with
  (qb.reward.default-methods) and the highest reward one attempt may win (qb.reward.max-per-attempt), each with its
  stored value and whether it exists yet; they change only through controlled changes (four eyes)
entities: [SysParam]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  paramKey:    { kind: { type: text, maxLength: 200 } }
  value:       { from: SysParam.value }
  paramExists: { kind: { type: bool } }
  seq:         { kind: { type: numeric, precision: 3, scale: 0 } }
list:
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [paramKey]
permissions: [qb.admin.publication.read]
---*/
-- paramExists is false until a proposal declaring the parameter is published (QB_SETUP proposes both); the processes
-- then use the declared values (PLATFORM alone, no cap).
WITH wanted (k, seq) AS (
    VALUES ('qb.reward.default-methods', 1), ('qb.reward.max-per-attempt', 2))
SELECT
    CAST(w.k AS varchar(200)) AS paramKey,
    p.{{SysParam.value}}      AS value,
    p.{{SysParam.paramKey}} IS NOT NULL AS paramExists,
    w.seq AS seq
FROM wanted w
LEFT JOIN {{SysParam}} p ON p.{{SysParam.paramKey}} = w.k
