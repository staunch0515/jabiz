/*---
id: qb.me.certificate-issuer
description: The issuer a certificate shows (Q77) - the operator's name only, from the controlled parameter qb.issuer.name
entities: [SysParam]
results:
  name: { kind: { type: text, maxLength: 200 } }
permissions: [qb.me]
---*/
-- Always one row; the certificate shows no issuer line when the name is empty.
SELECT
    CAST(max(NULLIF(p.{{SysParam.value}}, '')) AS varchar(200)) AS name
FROM {{SysParam}} p
WHERE p.{{SysParam.paramKey}} = 'qb.issuer.name'
