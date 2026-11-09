/*---
id: qb.sponsor.layout
description: >-
  F-F3: the Sponsor app frame's parameters - the areas of the bottom menu on a phone (the uncontrolled parameter
  qb.sponsor.bottom-bar, comma-separated area keys; none before the setup made it: the page keeps its default)
entities: [SysParam]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  bottomBar: { kind: { type: text, maxLength: 200 } }
permissions: [qb.publication.write]
---*/
-- One row, the parameter's value as text (the page leaves out unknown keys and repeats).
SELECT CAST(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.sponsor.bottom-bar' THEN CAST(p.{{SysParam.value}} AS text) END)
            AS varchar(200)) AS bottomBar
FROM {{SysParam}} p
WHERE p.{{SysParam.paramKey}} = 'qb.sponsor.bottom-bar'
