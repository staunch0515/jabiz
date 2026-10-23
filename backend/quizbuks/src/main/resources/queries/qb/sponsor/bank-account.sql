/*---
id: qb.sponsor.bank-account
description: The platform's account sponsors transfer bank top-ups to (Q13a), from the controlled parameters qb.bank.receiving.*, with the limits of a bank top-up
entities: [SysParam]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  bank:          { kind: { type: text, maxLength: 100 } }
  branch:        { kind: { type: text, maxLength: 100 } }
  accountType:   { kind: { type: text, maxLength: 20 } }
  accountNumber: { kind: { type: text, maxLength: 20 } }
  accountHolder: { kind: { type: text, maxLength: 100 } }
  min:           { kind: { type: monetary, currency: JPY, scale: 0 } }
  max:           { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.topup]
---*/
-- Always one row; an empty value is none. Bank top-ups are refused until every part of the account is set
-- (QB_TOPUP_BANK_START reads the same parameters, at the server's clock as this dataset does).
WITH params AS (
    SELECT p.{{SysParam.paramKey}} AS k, NULLIF(p.{{SysParam.value}}, '') AS v
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.bank.receiving.bank', 'qb.bank.receiving.branch',
        'qb.bank.receiving.account-type', 'qb.bank.receiving.account-number', 'qb.bank.receiving.account-holder',
        'qb.topup.bank.min', 'qb.topup.bank.max'))
SELECT
    CAST(max(CASE WHEN k = 'qb.bank.receiving.bank' THEN v END) AS varchar(100))          AS bank,
    CAST(max(CASE WHEN k = 'qb.bank.receiving.branch' THEN v END) AS varchar(100))        AS branch,
    CAST(max(CASE WHEN k = 'qb.bank.receiving.account-type' THEN v END) AS varchar(20))   AS accountType,
    CAST(max(CASE WHEN k = 'qb.bank.receiving.account-number' THEN v END) AS varchar(20)) AS accountNumber,
    CAST(max(CASE WHEN k = 'qb.bank.receiving.account-holder' THEN v END) AS varchar(100)) AS accountHolder,
    CAST(max(CASE WHEN k = 'qb.topup.bank.min' AND v ~ '^[0-9]{1,18}$' THEN v END) AS numeric) AS min,
    CAST(max(CASE WHEN k = 'qb.topup.bank.max' AND v ~ '^[0-9]{1,18}$' THEN v END) AS numeric) AS max
FROM params
