/*---
id: qb.sponsor.receipt-issuer
description: The issuer the top-up receipts show (Q9d, F-09, D-4) - the operator's name, address and registration number, from the controlled parameters qb.issuer.*
entities: [SysParam]
results:
  name:           { kind: { type: text, maxLength: 200 } }
  address:        { kind: { type: text, maxLength: 500 } }
  registrationNo: { kind: { type: text, maxLength: 50 } }
permissions: [qb.topup]
---*/
-- Always one row (an empty value is none): QB_TOPUP_RECEIPT_ISSUE refuses to issue without a name.
SELECT
    CAST(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.issuer.name' THEN NULLIF(p.{{SysParam.value}}, '') END)
        AS varchar(200)) AS name,
    CAST(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.issuer.address' THEN NULLIF(p.{{SysParam.value}}, '') END)
        AS varchar(500)) AS address,
    CAST(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.issuer.registration-no'
        THEN NULLIF(p.{{SysParam.value}}, '') END) AS varchar(50)) AS registrationNo
FROM {{SysParam}} p
WHERE p.{{SysParam.paramKey}} IN ('qb.issuer.name', 'qb.issuer.address', 'qb.issuer.registration-no')
