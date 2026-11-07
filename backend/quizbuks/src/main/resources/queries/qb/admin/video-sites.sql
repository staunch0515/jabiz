/*---
id: qb.admin.video-sites
description: >-
  The video sites mainland China plays (F-S11, the controlled parameter qb.video.mainland-accessible-sites; no row
  before its first value is published), for the review page's notice that mainland China cannot see a publication
entities: [SysParam]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  sites: { kind: { type: text, maxLength: 200 } }
list:
  key: [sites]
permissions: [qb.admin.publication.read]
---*/
-- Only this one parameter: the reviewers (operators' members among them) read no other parameter.
SELECT CAST(p.{{SysParam.value}} AS varchar(200)) AS sites
FROM {{SysParam}} p
WHERE p.{{SysParam.paramKey}} = 'qb.video.mainland-accessible-sites'
