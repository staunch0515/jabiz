/*---
id: qb.me.certificate-issuer
description: The issuer a certificate shows (Q77) - the operator's name from the controlled parameter qb.issuer.name, or (Q88a) the sponsor whose certification it is, given by the issue
entities: [SysParam]
params:
  issuerName: { kind: { type: text, maxLength: 200 }, description: "Q88a (D-Q88-6): the sponsor's name when the certification is a sponsor's own; else the platform's issuer" }
results:
  name: { kind: { type: text, maxLength: 200 } }
permissions: [qb.me]
---*/
-- Always one row; the certificate shows no issuer line when the name is empty.
SELECT
    CAST(coalesce(NULLIF(CAST(:issuerName AS text), ''), max(NULLIF(p.{{SysParam.value}}, ''))) AS varchar(200)) AS name
FROM {{SysParam}} p
WHERE p.{{SysParam.paramKey}} = 'qb.issuer.name'
