/*---
id: jabiz.ledger.account_activity
description: >-
  The activity of one account over a range of booking times: the opening balance, every entry with its transaction,
  source document, memo, dimensions and running balance, and the closing balance; as recorded up to a time, if given.
entities: [LedgerAccount, LedgerTransaction, LedgerEntry]
params:
  account: { like: LedgerAccount.accountCode, required: true, description: code of the account }
  from:    { like: LedgerTransaction.bookingTime, required: true, description: first booking time of the range }
  asOf:    { like: LedgerTransaction.bookingTime, required: true, description: last booking time of the range }
  knownAt: { like: LedgerTransaction.bookingTime, description: "if given, only transactions recorded at or before this time count" }
results:
  seq:            { kind: { type: numeric, precision: 18, scale: 0 } }
  rowKind:        { kind: { type: text, maxLength: 10 } }
  bookingTime:    { from: LedgerTransaction.bookingTime }
  transactionId:  { from: LedgerTransaction.transactionId }
  description:    { from: LedgerTransaction.description }
  reference:      { from: LedgerTransaction.reference }
  sourceEntity:   { from: LedgerTransaction.sourceEntity }
  sourceId:       { from: LedgerTransaction.sourceId }
  lineNo:         { from: LedgerEntry.lineNo }
  memo:           { from: LedgerEntry.memo }
  currency:       { from: LedgerEntry.currency }
  transactionAmount: { from: LedgerEntry.transactionAmount }
  exchangeRate:   { from: LedgerEntry.exchangeRate }
  dimension1:     { from: LedgerEntry.dimension1 }
  dimension2:     { from: LedgerEntry.dimension2 }
  dimension3:     { from: LedgerEntry.dimension3 }
  dimension4:     { from: LedgerEntry.dimension4 }
  debit:          { from: LedgerEntry.amount }
  credit:         { from: LedgerEntry.amount }
  runningBalance: { from: LedgerEntry.amount }
list:
  filters: [rowKind, sourceEntity, sourceId]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [seq]
permissions: [ledger.read]
---*/
WITH acct AS (
    SELECT a.{{LedgerAccount.accountId}} AS acct_key
    FROM {{LedgerAccount}} a
    WHERE a.{{LedgerAccount.accountCode}} = :account
),
entries AS (
    SELECT t.{{LedgerTransaction.bookingTime}} AS booked_at,
           t.{{LedgerTransaction.createdTime}} AS recorded_time,
           t.{{LedgerTransaction.transactionId}} AS tx_key,
           t.{{LedgerTransaction.description}} AS description,
           t.{{LedgerTransaction.reference}} AS reference,
           t.{{LedgerTransaction.sourceEntity}} AS src_entity,
           t.{{LedgerTransaction.sourceId}} AS src_key,
           e.{{LedgerEntry.lineNo}} AS line_number,
           e.{{LedgerEntry.memo}} AS memo,
           e.{{LedgerEntry.currency}} AS entry_currency,
           e.{{LedgerEntry.transactionAmount}} AS entry_foreign,
           e.{{LedgerEntry.exchangeRate}} AS entry_rate,
           e.{{LedgerEntry.dimension1}} AS dim_1,
           e.{{LedgerEntry.dimension2}} AS dim_2,
           e.{{LedgerEntry.dimension3}} AS dim_3,
           e.{{LedgerEntry.dimension4}} AS dim_4,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END AS debit,
           CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END AS credit
    FROM {{LedgerEntry}} e
    JOIN acct ON acct.acct_key = e.{{LedgerEntry.accountId}}
    JOIN {{LedgerTransaction}} t
      ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE t.{{LedgerTransaction.bookingTime}} <= :asOf
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
),
opening AS (
    SELECT COALESCE(SUM(debit - credit), 0) AS balance FROM entries WHERE booked_at < :from
),
lines AS (
    SELECT entries.*,
           ROW_NUMBER() OVER (ORDER BY booked_at, recorded_time, tx_key, line_number) AS position
    FROM entries
    WHERE booked_at >= :from
)
SELECT 0 AS seq, 'OPENING' AS rowKind, CAST(:from AS timestamptz) AS bookingTime, NULL AS transactionId,
       NULL AS description, NULL AS reference, NULL AS sourceEntity, NULL AS sourceId, NULL AS lineNo, NULL AS memo,
       NULL AS currency, NULL AS transactionAmount, NULL AS exchangeRate,
       NULL AS dimension1, NULL AS dimension2, NULL AS dimension3, NULL AS dimension4, NULL AS debit, NULL AS credit,
       opening.balance AS runningBalance
FROM opening
UNION ALL
SELECT lines.position, 'ENTRY', lines.booked_at, lines.tx_key, lines.description, lines.reference,
       lines.src_entity, lines.src_key, lines.line_number, lines.memo,
       lines.entry_currency, lines.entry_foreign, lines.entry_rate, lines.dim_1, lines.dim_2,
       lines.dim_3, lines.dim_4, lines.debit, lines.credit,
       opening.balance + SUM(lines.debit - lines.credit) OVER (ORDER BY lines.position)
FROM lines CROSS JOIN opening
UNION ALL
SELECT (SELECT COUNT(*) FROM lines) + 1, 'CLOSING', CAST(:asOf AS timestamptz), NULL, NULL, NULL, NULL, NULL, NULL,
       NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, SUM(lines.debit), SUM(lines.credit),
       opening.balance + COALESCE(SUM(lines.debit - lines.credit), 0)
FROM opening LEFT JOIN lines ON true
GROUP BY opening.balance
