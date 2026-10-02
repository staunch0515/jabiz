/*---
id: finance.bank.reconciliation
description: >-
  A bank account's reconciliation for a statement's closing day (FIN-BK-007, FIN-EXP-10): the balance per the
  statement; the book items not on the statement by then, deposits in transit and outstanding payments, carried
  forward until they are (FIN-BK-009); the adjusted bank balance; the balance per the books; the statement lines not
  in the books; and the difference. An item counts as on the statement only through a match whose lines and items
  all lie on or before the day, so a later match leaves the day as it was.
entities: [FinBankAccount, FinBankStatement, FinStatementLine, FinBankOpening, FinBankOpeningItem, LedgerAccount,
  LedgerTransaction, LedgerEntry, FinPosting, FinBankMatch, FinBankMatchItem]
params:
  bankCode:      { like: FinBankAccount.bankCode, required: true }
  statementDate: { like: FinBankStatement.toDate, required: true, description: the statement's closing day }
  preparedBy:    { kind: { type: text, maxLength: 100 }, description: who prepared it }
  reviewedBy:    { kind: { type: text, maxLength: 100 }, description: who signed it off }
results:
  seq:         { kind: { type: numeric, precision: 9, scale: 0 } }
  section:     { kind: { type: text, maxLength: 30 } }
  itemDate:    { from: FinStatementLine.valueDate }
  reference:   { kind: { type: text, maxLength: 100 } }
  description: { kind: { type: text, maxLength: 500 } }
  amount:      { from: FinStatementLine.amount }
list:
  filters: [section, reference]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [seq]
permissions: [fin.bank.activity.read]
report:
  period: { from: statementDate, to: statementDate }
---*/
WITH bank AS (
    SELECT b.{{FinBankAccount.glAccount}} AS gl
    FROM {{FinBankAccount}} b
    WHERE b.{{FinBankAccount.bankCode}} = :bankCode
),
cutover AS (
    SELECT o.{{FinBankOpening.cutoverDate}} AS cutover_day
    FROM {{FinBankOpening}} o
    WHERE o.{{FinBankOpening.bankCode}} = :bankCode
),
statement AS (
    SELECT s.{{FinBankStatement.closingBalance}} AS closing
    FROM {{FinBankStatement}} s
    WHERE s.{{FinBankStatement.bankCode}} = :bankCode AND s.{{FinBankStatement.toDate}} = :statementDate
),
cash AS (
    SELECT a.{{LedgerAccount.accountId}} AS account_key
    FROM {{LedgerAccount}} a
    JOIN bank ON bank.gl = a.{{LedgerAccount.accountCode}}
),
moves AS (
    SELECT fp.{{FinPosting.transactionId}} AS txn,
           fp.{{FinPosting.postingDate}} AS posted_on,
           fp.{{FinPosting.documentNo}} AS doc,
           t.{{LedgerTransaction.description}} AS txt,
           SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                    ELSE -e.{{LedgerEntry.amount}} END) AS amt
    FROM {{LedgerEntry}} e
    JOIN cash ON cash.account_key = e.{{LedgerEntry.accountId}}
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE fp.{{FinPosting.postingDate}} <= :statementDate
    GROUP BY fp.{{FinPosting.transactionId}}, fp.{{FinPosting.postingDate}}, fp.{{FinPosting.documentNo}},
             t.{{LedgerTransaction.description}}
),
settled AS (
    -- Matches not undone whose every line and item lies on or before the day.
    SELECT m.{{FinBankMatch.matchId}} AS match_key
    FROM {{FinBankMatch}} m
    JOIN {{FinBankMatchItem}} i ON i.{{FinBankMatchItem.matchId}} = m.{{FinBankMatch.matchId}}
    WHERE m.{{FinBankMatch.bankCode}} = :bankCode AND m.{{FinBankMatch.action}} = 'MATCH'
      AND NOT EXISTS (SELECT 1 FROM {{FinBankMatch}} u
                      WHERE u.{{FinBankMatch.reversesMatchId}} = m.{{FinBankMatch.matchId}})
    GROUP BY m.{{FinBankMatch.matchId}}
    HAVING MAX(i.{{FinBankMatchItem.itemDate}}) <= :statementDate
),
settled_refs AS (
    SELECT i.{{FinBankMatchItem.refKind}} AS kind_code, i.{{FinBankMatchItem.refId}} AS ref_key
    FROM {{FinBankMatchItem}} i
    JOIN settled ON settled.match_key = i.{{FinBankMatchItem.matchId}}
),
open_book AS (
    SELECT mv.posted_on AS on_day, mv.doc AS ref_text, mv.txt AS about, mv.amt AS amt
    FROM moves mv
    JOIN cutover ON mv.posted_on > cutover.cutover_day
    WHERE mv.amt <> 0
      AND NOT EXISTS (SELECT 1 FROM settled_refs r
                      WHERE r.kind_code = 'LEDGER' AND r.ref_key = CAST(mv.txn AS varchar))
    UNION ALL
    SELECT o.{{FinBankOpeningItem.itemDate}}, o.{{FinBankOpeningItem.reference}}, o.{{FinBankOpeningItem.description}},
           o.{{FinBankOpeningItem.amount}}
    FROM {{FinBankOpeningItem}} o
    WHERE o.{{FinBankOpeningItem.bankCode}} = :bankCode
      AND NOT EXISTS (SELECT 1 FROM settled_refs r
                      WHERE r.kind_code = 'OPENING'
                        AND r.ref_key = CAST(o.{{FinBankOpeningItem.itemId}} AS varchar))
),
open_lines AS (
    SELECT l.{{FinStatementLine.valueDate}} AS on_day,
           COALESCE(l.{{FinStatementLine.bankReference}}, '') AS ref_text,
           l.{{FinStatementLine.description}} AS about, l.{{FinStatementLine.amount}} AS amt
    FROM {{FinStatementLine}} l
    WHERE l.{{FinStatementLine.bankCode}} = :bankCode AND l.{{FinStatementLine.valueDate}} <= :statementDate
      AND NOT EXISTS (SELECT 1 FROM settled_refs r
                      WHERE r.kind_code = 'LINE'
                        AND r.ref_key = CAST(l.{{FinStatementLine.lineId}} AS varchar))
),
figures AS (
    SELECT (SELECT closing FROM statement) AS stmt_total,
           (SELECT COALESCE(SUM(amt), 0) FROM open_book WHERE amt > 0) AS deposits,
           (SELECT COALESCE(SUM(amt), 0) FROM open_book WHERE amt < 0) AS payments,
           (SELECT COALESCE(SUM(amt), 0) FROM moves) AS book,
           (SELECT COALESCE(SUM(amt), 0) FROM open_lines) AS not_in_books
),
report_rows AS (
    SELECT 1 AS part, CAST(:statementDate AS date) AS on_day, CAST(NULL AS varchar) AS ref_text,
           'Balance per bank statement' AS about, f.stmt_total AS amt
    FROM figures f
    UNION ALL
    SELECT 2, ob.on_day, ob.ref_text, ob.about, ob.amt FROM open_book ob WHERE ob.amt > 0
    UNION ALL
    SELECT 3, ob.on_day, ob.ref_text, ob.about, ob.amt FROM open_book ob WHERE ob.amt < 0
    UNION ALL
    SELECT 4, CAST(:statementDate AS date), NULL, 'Adjusted bank balance',
           f.stmt_total + f.deposits + f.payments
    FROM figures f
    UNION ALL
    SELECT 5, CAST(:statementDate AS date), NULL, 'Balance per books', f.book FROM figures f
    UNION ALL
    SELECT 6, ol.on_day, ol.ref_text, ol.about, ol.amt FROM open_lines ol
    UNION ALL
    SELECT 7, CAST(:statementDate AS date), NULL, 'Difference',
           f.stmt_total + f.deposits + f.payments - f.book
    FROM figures f
    UNION ALL
    SELECT 8, CAST(NULL AS date), NULL, :preparedBy, NULL WHERE CAST(:preparedBy AS varchar) IS NOT NULL
    UNION ALL
    SELECT 9, CAST(NULL AS date), NULL, :reviewedBy, NULL WHERE CAST(:reviewedBy AS varchar) IS NOT NULL
)
SELECT ROW_NUMBER() OVER (ORDER BY r.part, r.on_day, r.ref_text, r.amt, r.about) AS seq,
       CASE r.part WHEN 1 THEN 'STATEMENT_BALANCE' WHEN 2 THEN 'DEPOSIT_IN_TRANSIT'
                   WHEN 3 THEN 'OUTSTANDING_PAYMENT' WHEN 4 THEN 'ADJUSTED_BANK_BALANCE'
                   WHEN 5 THEN 'BOOK_BALANCE' WHEN 6 THEN 'NOT_IN_BOOKS' WHEN 7 THEN 'DIFFERENCE'
                   WHEN 8 THEN 'PREPARED_BY' ELSE 'REVIEWED_BY' END AS section,
       r.on_day AS itemDate, r.ref_text AS reference, r.about AS description, r.amt AS amount
FROM report_rows r
