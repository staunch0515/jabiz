/*---
id: finance.bank.statement_items
description: >-
  The statement lines of a bank account not matched to its books (FIN-BK-004, 005): what matching works on, and the
  other reconciling items of a reconciliation.
entities: [FinStatementLine, FinBankMatch, FinBankMatchItem]
params:
  bankCode: { like: FinStatementLine.bankCode, required: true }
  to:       { like: FinStatementLine.valueDate, description: "the last day of the lines; all when not given" }
results:
  lineId:        { from: FinStatementLine.lineId }
  valueDate:     { from: FinStatementLine.valueDate }
  bankReference: { from: FinStatementLine.bankReference }
  description:   { from: FinStatementLine.description }
  amount:        { from: FinStatementLine.amount }
  typeCode:      { from: FinStatementLine.typeCode }
list:
  filters: [lineId, valueDate, bankReference, description, amount]
  sorts:   [valueDate, bankReference, amount]
  defaultSort: { field: valueDate, asc: true }
  key: [lineId]
permissions: [fin.bank.activity.read]
---*/
SELECT s.{{FinStatementLine.lineId}} AS lineId, s.{{FinStatementLine.valueDate}} AS valueDate,
       s.{{FinStatementLine.bankReference}} AS bankReference, s.{{FinStatementLine.description}} AS description,
       s.{{FinStatementLine.amount}} AS amount, s.{{FinStatementLine.typeCode}} AS typeCode
FROM {{FinStatementLine}} s
WHERE s.{{FinStatementLine.bankCode}} = :bankCode
  AND (CAST(:to AS date) IS NULL OR s.{{FinStatementLine.valueDate}} <= :to)
  AND NOT EXISTS (
      SELECT 1
      FROM {{FinBankMatchItem}} i
      JOIN {{FinBankMatch}} m ON m.{{FinBankMatch.matchId}} = i.{{FinBankMatchItem.matchId}}
      WHERE i.{{FinBankMatchItem.refKind}} = 'LINE'
        AND i.{{FinBankMatchItem.refId}} = CAST(s.{{FinStatementLine.lineId}} AS varchar)
        AND NOT EXISTS (SELECT 1 FROM {{FinBankMatch}} u
                        WHERE u.{{FinBankMatch.reversesMatchId}} = m.{{FinBankMatch.matchId}}))
