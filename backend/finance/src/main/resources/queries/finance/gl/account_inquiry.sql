/*---
id: finance.gl.account_inquiry
description: >-
  One account over a range of posting dates (FIN-GL-022): the opening balance, every posted line with its general
  ledger number, source document, memo, dimensions and running balance, and the closing balance; the books as recorded
  up to a time, if given.
entities: [LedgerAccount, LedgerTransaction, LedgerEntry, FinPosting]
params:
  account: { like: LedgerAccount.accountCode, required: true, description: code of the account }
  from:    { like: FinPosting.postingDate, required: true, description: first posting date of the range }
  to:      { like: FinPosting.postingDate, required: true, description: last posting date of the range }
  knownAt: { like: LedgerTransaction.bookingTime, description: "if given, the books as recorded at this time" }
results:
  seq:            { kind: { type: numeric, precision: 18, scale: 0 } }
  rowKind:        { kind: { type: text, maxLength: 10 } }
  postingDate:    { from: FinPosting.postingDate }
  glNo:           { from: FinPosting.glNo }
  documentNo:     { from: FinPosting.documentNo }
  description:    { from: LedgerTransaction.description }
  sourceEntity:   { from: FinPosting.sourceEntity }
  sourceId:       { from: FinPosting.sourceId }
  lineNo:         { from: LedgerEntry.lineNo }
  memo:           { from: LedgerEntry.memo }
  department:     { from: LedgerEntry.dimension1 }
  location:       { from: LedgerEntry.dimension2 }
  debit:          { from: LedgerEntry.amount }
  credit:         { from: LedgerEntry.amount }
  runningBalance: { from: LedgerEntry.amount }
list:
  filters: [rowKind, sourceEntity, sourceId, documentNo, department, location]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [seq]
permissions: [ledger.read]
timeSlice: { knownAt: knownAt }
report:
  period: { from: from, to: to }
  landscape: true
---*/
WITH acct AS (
    SELECT a.{{LedgerAccount.accountId}} AS acct_key
    FROM {{LedgerAccount}} a
    WHERE a.{{LedgerAccount.accountCode}} = :account
),
entries AS (
    SELECT fp.{{FinPosting.postingDate}} AS posted_on,
           fp.{{FinPosting.glNo}} AS gl_number,
           fp.{{FinPosting.documentNo}} AS document_number,
           fp.{{FinPosting.sourceEntity}} AS src_entity,
           fp.{{FinPosting.sourceId}} AS src_key,
           t.{{LedgerTransaction.createdTime}} AS recorded_time,
           t.{{LedgerTransaction.description}} AS description,
           e.{{LedgerEntry.lineNo}} AS line_number,
           e.{{LedgerEntry.memo}} AS memo,
           e.{{LedgerEntry.dimension1}} AS dim_1,
           e.{{LedgerEntry.dimension2}} AS dim_2,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END AS debit,
           CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END AS credit
    FROM {{LedgerEntry}} e
    JOIN acct ON acct.acct_key = e.{{LedgerEntry.accountId}}
    JOIN {{LedgerTransaction}} t
      ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp
      ON fp.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
    WHERE fp.{{FinPosting.postingDate}} <= :to
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
),
opening AS (
    SELECT COALESCE(SUM(debit - credit), 0) AS balance FROM entries WHERE posted_on < :from
),
lines AS (
    SELECT entries.*,
           ROW_NUMBER() OVER (ORDER BY posted_on, gl_number, line_number) AS position
    FROM entries
    WHERE posted_on >= :from
)
SELECT 0 AS seq, 'OPENING' AS rowKind, CAST(:from AS date) AS postingDate, NULL AS glNo, NULL AS documentNo,
       NULL AS description, NULL AS sourceEntity, NULL AS sourceId, NULL AS lineNo, NULL AS memo,
       NULL AS department, NULL AS location, NULL AS debit, NULL AS credit, opening.balance AS runningBalance
FROM opening
UNION ALL
SELECT lines.position, 'ENTRY', lines.posted_on, lines.gl_number, lines.document_number, lines.description,
       lines.src_entity, lines.src_key, lines.line_number, lines.memo, lines.dim_1, lines.dim_2,
       lines.debit, lines.credit,
       opening.balance + SUM(lines.debit - lines.credit) OVER (ORDER BY lines.position)
FROM lines CROSS JOIN opening
UNION ALL
SELECT (SELECT COUNT(*) FROM lines) + 1, 'CLOSING', CAST(:to AS date), NULL, NULL, NULL, NULL, NULL, NULL, NULL,
       NULL, NULL, SUM(lines.debit), SUM(lines.credit),
       opening.balance + COALESCE(SUM(lines.debit - lines.credit), 0)
FROM opening LEFT JOIN lines ON true
GROUP BY opening.balance
