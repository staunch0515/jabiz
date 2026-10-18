/*---
id: qb.me.support
description: Where a signed-in user asks for help (U-72 "still need help", Q7-8) - the support address, if set
entities: [SysParam]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  email: { kind: { type: text, maxLength: 200 } }
permissions: [qb.account.read]
---*/
-- One row; email is null (the page shows no contact) while qb.support.email is empty or not set. Read at the server's
-- clock (the dataset without time travel).
SELECT nullif(btrim(max(p.{{SysParam.value}})), '') AS email
FROM {{SysParam}} p
WHERE p.{{SysParam.paramKey}} = 'qb.support.email'
