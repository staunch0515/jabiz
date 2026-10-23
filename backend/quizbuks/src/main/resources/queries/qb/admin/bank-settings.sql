/*---
id: qb.admin.bank-settings
description: >-
  The bank top-up's settings in force (Q18a): the platform's receiving account and the limits, each with its stored
  value and whether it exists yet; they change only through controlled changes (QB_BANK_SETTINGS_PROPOSE, four eyes)
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
permissions: [qb.admin.finance.read]
---*/
-- In the order of BankSettings.KEYS. paramExists is false until a proposal declaring the parameter is published.
WITH wanted (k, seq) AS (
    VALUES ('qb.bank.receiving.bank', 1), ('qb.bank.receiving.branch', 2), ('qb.bank.receiving.account-type', 3),
           ('qb.bank.receiving.account-number', 4), ('qb.bank.receiving.account-holder', 5),
           ('qb.topup.bank.min', 6), ('qb.topup.bank.max', 7))
SELECT
    CAST(w.k AS varchar(200)) AS paramKey,
    p.{{SysParam.value}}      AS value,
    p.{{SysParam.paramKey}} IS NOT NULL AS paramExists,
    w.seq AS seq
FROM wanted w
LEFT JOIN {{SysParam}} p ON p.{{SysParam.paramKey}} = w.k
