/*---
id: finance.bank.match_history
description: >-
  Every match of a bank account and every undo, with who, when, how and why, and what each brought together
  (FIN-BK-006): an undone match stays, its undo following it.
entities: [FinBankMatch, FinBankMatchItem]
params:
  bankCode: { like: FinBankMatch.bankCode, required: true }
results:
  matchId:         { from: FinBankMatch.matchId }
  action:          { from: FinBankMatch.action }
  reversesMatchId: { from: FinBankMatch.reversesMatchId }
  method:          { from: FinBankMatch.method }
  amount:          { from: FinBankMatch.amount }
  confidence:      { from: FinBankMatch.confidence }
  reason:          { from: FinBankMatch.reason }
  actor:           { from: FinBankMatch.actor }
  actionTime:      { from: FinBankMatch.actionTime }
  statementItems:  { kind: { type: text, maxLength: 2000 } }
  bookItems:       { kind: { type: text, maxLength: 2000 } }
list:
  filters: [matchId, action, method, actor, actionTime, statementItems, bookItems]
  sorts:   [actionTime]
  defaultSort: { field: actionTime, asc: true }
  key: [matchId]
permissions: [fin.bank.activity.read]
---*/
WITH items AS (
    SELECT i.{{FinBankMatchItem.matchId}} AS match_key,
           string_agg(CASE WHEN i.{{FinBankMatchItem.side}} = 'STATEMENT' THEN i.{{FinBankMatchItem.label}} END,
                      ', ' ORDER BY i.{{FinBankMatchItem.itemDate}}, i.{{FinBankMatchItem.label}}) AS statement_items,
           string_agg(CASE WHEN i.{{FinBankMatchItem.side}} = 'BOOK' THEN i.{{FinBankMatchItem.label}} END,
                      ', ' ORDER BY i.{{FinBankMatchItem.itemDate}}, i.{{FinBankMatchItem.label}}) AS book_items
    FROM {{FinBankMatchItem}} i
    WHERE i.{{FinBankMatchItem.bankCode}} = :bankCode
    GROUP BY i.{{FinBankMatchItem.matchId}}
)
SELECT m.{{FinBankMatch.matchId}} AS matchId, m.{{FinBankMatch.action}} AS action,
       m.{{FinBankMatch.reversesMatchId}} AS reversesMatchId, m.{{FinBankMatch.method}} AS method,
       m.{{FinBankMatch.amount}} AS amount, m.{{FinBankMatch.confidence}} AS confidence,
       m.{{FinBankMatch.reason}} AS reason, m.{{FinBankMatch.actor}} AS actor,
       m.{{FinBankMatch.actionTime}} AS actionTime,
       COALESCE(own.statement_items, undone.statement_items) AS statementItems,
       COALESCE(own.book_items, undone.book_items) AS bookItems
FROM {{FinBankMatch}} m
LEFT JOIN items own ON own.match_key = m.{{FinBankMatch.matchId}}
LEFT JOIN items undone ON undone.match_key = m.{{FinBankMatch.reversesMatchId}}
WHERE m.{{FinBankMatch.bankCode}} = :bankCode
