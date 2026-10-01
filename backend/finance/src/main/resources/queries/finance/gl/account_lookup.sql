/*---
id: finance.gl.account_lookup
description: >-
  The accounts as an entry form needs them for type-ahead and checks while typing (FIN-UI-002): code, name, whether it
  is active or a summary account, its control class and the dimension it requires. The server checks again on submission.
entities: [LedgerAccount, FinAccount]
results:
  accountCode:       { from: LedgerAccount.accountCode }
  accountName:       { from: LedgerAccount.accountName }
  active:            { from: LedgerAccount.enabled }
  summary:           { from: LedgerAccount.summary }
  financialType:     { from: FinAccount.financialType }
  controlClass:      { from: FinAccount.controlClass }
  requiredDimension: { from: FinAccount.requiredDimension }
list:
  filters: [accountCode, active, summary, financialType]
  sorts:   [accountCode]
  defaultSort: { field: accountCode, asc: true }
  key: [accountCode]
permissions: [fin.account.read]
---*/
SELECT
    a.{{LedgerAccount.accountCode}}  AS accountCode,
    a.{{LedgerAccount.accountName}}  AS accountName,
    COALESCE(a.{{LedgerAccount.enabled}}, false) AS active,
    COALESCE(a.{{LedgerAccount.summary}}, false) AS summary,
    f.{{FinAccount.financialType}}   AS financialType,
    f.{{FinAccount.controlClass}}    AS controlClass,
    f.{{FinAccount.requiredDimension}} AS requiredDimension
FROM {{LedgerAccount}} a
JOIN {{FinAccount}} f ON f.{{FinAccount.ledgerAccountId}} = a.{{LedgerAccount.accountId}}
