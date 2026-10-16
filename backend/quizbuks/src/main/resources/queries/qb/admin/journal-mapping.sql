/*---
id: qb.admin.journal-mapping
description: >-
  The account mapping of the journal export (Q9d, F-01): each postable account of the ledger with the accounting
  system's account code now in effect (empty when not set) and the parameter that holds it; it changes only through a
  controlled change
entities: [LedgerAccount, SysParam]
results:
  accountCode:  { from: LedgerAccount.accountCode }
  accountName:  { from: LedgerAccount.accountName }
  paramKey:     { kind: { type: text, maxLength: 200 } }
  externalCode: { kind: { type: text, maxLength: 20 } }
  paramExists:  { kind: { type: bool } }
list:
  sorts:   [accountCode]
  defaultSort: { field: accountCode, asc: true }
  key: [accountCode]
permissions: [qb.admin.finance.read]
---*/
-- paramExists is false until the setup's proposal of the parameter is published.
SELECT
    a.{{LedgerAccount.accountCode}} AS accountCode,
    a.{{LedgerAccount.accountName}} AS accountName,
    CAST('qb.journal.account.' || a.{{LedgerAccount.accountCode}} AS varchar(200)) AS paramKey,
    CAST(NULLIF(p.{{SysParam.value}}, '') AS varchar(20)) AS externalCode,
    p.{{SysParam.paramKey}} IS NOT NULL AS paramExists
FROM {{LedgerAccount}} a
LEFT JOIN {{SysParam}} p
    ON p.{{SysParam.paramKey}} = 'qb.journal.account.' || a.{{LedgerAccount.accountCode}}
WHERE NOT coalesce(a.{{LedgerAccount.summary}}, false)
