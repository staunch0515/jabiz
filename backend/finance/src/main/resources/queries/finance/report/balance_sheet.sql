/*---
id: finance.report.balance_sheet
description: >-
  The classified balance sheet on a day (FIN-RP-002; FIN-EXP-05), with the end of the month before and the end of the
  fiscal year before beside it: each line of the layout, a line per account where the layout asks, the totals, and at
  the end any account with a balance in some column on no line of the layout (unmapped: FIN_STATEMENT_ISSUE refuses
  to issue it); a comparative column whose day is in no period is left empty.
  Retained earnings take the income not yet closed; closing entries never count. Read from the period balances.
entities: [FinStatementLayout, FinStatementLayoutRow, LedgerAccount, LedgerTransaction, LedgerEntry, FinAccount,
  FinPosting, FinPeriod, FinPeriodBalance]
params:
  asOf:          { like: FinPosting.postingDate, required: true, description: "the day of the balance sheet, at its end" }
  layout:        { like: FinStatementLayout.layoutCode, description: "the layout's code; BS when not given" }
  layoutVersion: { like: FinStatementLayout.version, description: "the layout's version; the latest when not given" }
  knownAt:       { like: LedgerTransaction.bookingTime, description: "if given, the books as recorded at this time" }
results:
  seq:      { kind: { type: numeric, precision: 9, scale: 0 } }
  lineCode: { kind: { type: text, maxLength: 60 } }
  label:    { kind: { type: text, maxLength: 300 } }
  kind:     { kind: { type: text, maxLength: 10 } }
  amount: { from: LedgerEntry.amount }
  priorMonth: { from: LedgerEntry.amount }
  priorYear: { from: LedgerEntry.amount }
list:
  filters: [lineCode, kind]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [lineCode]
permissions: [ledger.read]
timeSlice: { knownAt: knownAt }
report:
  period: { to: asOf }
---*/
WITH cutoffs (col, day) AS (
    SELECT 'CUR', CAST(:asOf AS date)
    UNION ALL
    SELECT 'PM', CAST((SELECT MAX(p.{{FinPeriod.startDate}}) FROM {{FinPeriod}} p
                       WHERE p.{{FinPeriod.startDate}} <= :asOf AND p.{{FinPeriod.endDate}} >= :asOf
                         AND NOT COALESCE(p.{{FinPeriod.adjustment}}, false)
                         AND NOT COALESCE(p.{{FinPeriod.opening}}, false)) - 1 AS date)
    UNION ALL
    SELECT 'PY', CAST((SELECT MIN(p.{{FinPeriod.startDate}}) FROM {{FinPeriod}} p
                       WHERE p.{{FinPeriod.fiscalYear}} = (
                           SELECT MAX(y.{{FinPeriod.fiscalYear}}) FROM {{FinPeriod}} y
                           WHERE y.{{FinPeriod.startDate}} <= :asOf AND y.{{FinPeriod.endDate}} >= :asOf)
                         AND NOT COALESCE(p.{{FinPeriod.opening}}, false)) - 1 AS date)
),
-- The layout version: the one asked for, else the latest; a layout of another statement gives nothing.
lay AS (
    SELECT l.{{FinStatementLayout.layoutId}} AS layout_key
    FROM {{FinStatementLayout}} l
    WHERE l.{{FinStatementLayout.layoutCode}} = COALESCE(CAST(:layout AS text), 'BS')
      AND l.{{FinStatementLayout.statement}} = 'BALANCE_SHEET'
      AND l.{{FinStatementLayout.version}} = COALESCE(CAST(:layoutVersion AS numeric), (
          SELECT MAX(m.{{FinStatementLayout.version}}) FROM {{FinStatementLayout}} m
          WHERE m.{{FinStatementLayout.layoutCode}} = COALESCE(CAST(:layout AS text), 'BS')))
),
lrows AS (
    SELECT r.{{FinStatementLayoutRow.seq}} AS seq_no, r.{{FinStatementLayoutRow.lineCode}} AS line,
           r.{{FinStatementLayoutRow.label}} AS caption, r.{{FinStatementLayoutRow.kind}} AS row_kind,
           r.{{FinStatementLayoutRow.accounts}} AS ranges, r.{{FinStatementLayoutRow.sign}} AS shown,
           r.{{FinStatementLayoutRow.detail}} AS each_account, r.{{FinStatementLayoutRow.omitZero}} AS skip_zero,
           r.{{FinStatementLayoutRow.noteAccounts}} AS note_ranges
    FROM {{FinStatementLayoutRow}} r JOIN lay ON r.{{FinStatementLayoutRow.layoutId}} = lay.layout_key
),
accts AS (
    SELECT a.{{LedgerAccount.accountCode}} AS code, a.{{LedgerAccount.accountName}} AS acct_label,
           f.{{FinAccount.financialType}} AS fin_type
    FROM {{LedgerAccount}} a
    JOIN {{FinAccount}} f ON f.{{FinAccount.ledgerAccountId}} = a.{{LedgerAccount.accountId}}
    WHERE NOT COALESCE(a.{{LedgerAccount.summary}}, false)
),
-- Which accounts each row takes: its ranges of codes, compared as text.
matched AS (
    SELECT r.line, ac.code
    FROM lrows r JOIN accts ac ON r.ranges IS NOT NULL AND EXISTS (
        SELECT 1 FROM UNNEST(STRING_TO_ARRAY(r.ranges, ',')) AS rng(part)
        WHERE ac.code COLLATE "C" BETWEEN SPLIT_PART(rng.part, '-', 1)
              AND COALESCE(NULLIF(SPLIT_PART(rng.part, '-', 2), ''), SPLIT_PART(rng.part, '-', 1)))
),
noted AS (
    SELECT r.line, ac.code
    FROM lrows r JOIN accts ac ON r.note_ranges IS NOT NULL AND EXISTS (
        SELECT 1 FROM UNNEST(STRING_TO_ARRAY(r.note_ranges, ',')) AS rng(part)
        WHERE ac.code COLLATE "C" BETWEEN SPLIT_PART(rng.part, '-', 1)
              AND COALESCE(NULLIF(SPLIT_PART(rng.part, '-', 2), ''), SPLIT_PART(rng.part, '-', 1)))
),
-- Balances on each cut-off day (ROADMAP F9 decision D1): a period wholly by the day from its latest snapshot and the
-- entries recorded after it, or entry by entry without a snapshot; a period the day falls in, entry by entry. Closing
-- entries never count: the statements show the year's income, and retained earnings take it in as well.
periods AS (
    SELECT p.{{FinPeriod.periodKey}} AS pkey, p.{{FinPeriod.startDate}} AS start_day,
           p.{{FinPeriod.endDate}} AS end_day, COALESCE(p.{{FinPeriod.adjustment}}, false) AS adj
    FROM {{FinPeriod}} p
    WHERE true
),
snapshots AS (
    SELECT s.{{FinPeriodBalance.periodKey}} AS pkey, MAX(s.{{FinPeriodBalance.countedTo}}) AS counted
    FROM {{FinPeriodBalance}} s
    JOIN periods pr ON pr.pkey = s.{{FinPeriodBalance.periodKey}}
    -- Period 13 holds the closing entries: always entry by entry.
    WHERE NOT pr.adj
      AND (CAST(:knownAt AS timestamptz) IS NULL OR s.{{FinPeriodBalance.countedTo}} <= :knownAt)
    GROUP BY s.{{FinPeriodBalance.periodKey}}
),
entries AS (
    SELECT fp.{{FinPosting.periodKey}} AS pkey, fp.{{FinPosting.postingDate}} AS day,
           a.{{LedgerAccount.accountCode}} AS code, t.{{LedgerTransaction.createdTime}} AS recorded,
           sn.counted AS counted,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END AS net
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    JOIN periods pr ON pr.pkey = fp.{{FinPosting.periodKey}}
    LEFT JOIN snapshots sn ON sn.pkey = fp.{{FinPosting.periodKey}}
    WHERE EXISTS (SELECT 1 FROM lay)
      -- CLS: the general ledger source of the year-end closing entries (JournalEntities.postingSource).
      AND fp.{{FinPosting.source}} <> 'CLS'
      AND fp.{{FinPosting.postingDate}} <= (SELECT MAX(c.day) FROM cutoffs c)
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
      -- After the snapshot only, unless a cut-off day falls in the period.
      AND (sn.counted IS NULL OR t.{{LedgerTransaction.createdTime}} > sn.counted OR EXISTS (
           SELECT 1 FROM cutoffs c WHERE c.day >= pr.start_day AND c.day < pr.end_day))
),
period_net AS (
    SELECT s.{{FinPeriodBalance.periodKey}} AS pkey, s.{{FinPeriodBalance.accountCode}} AS code,
           s.{{FinPeriodBalance.debit}} - s.{{FinPeriodBalance.credit}} AS net
    FROM {{FinPeriodBalance}} s
    JOIN snapshots sn ON sn.pkey = s.{{FinPeriodBalance.periodKey}} AND sn.counted = s.{{FinPeriodBalance.countedTo}}
    WHERE EXISTS (SELECT 1 FROM lay)
    UNION ALL
    SELECT en.pkey, en.code, en.net FROM entries en WHERE en.counted IS NULL OR en.recorded > en.counted
),
balances AS (
    SELECT col, code, SUM(net) AS net FROM (
        SELECT c.col, pn.code, pn.net
        FROM cutoffs c JOIN period_net pn ON true JOIN periods pr ON pr.pkey = pn.pkey
        WHERE pr.end_day <= c.day
        UNION ALL
        SELECT c.col, en.code, en.net
        FROM cutoffs c JOIN entries en ON en.day <= c.day JOIN periods pr ON pr.pkey = en.pkey
        WHERE pr.end_day > c.day AND pr.start_day <= c.day
    ) parts
    GROUP BY col, code
),
pivoted AS (
    SELECT b.code,
           SUM(CASE WHEN b.col = 'CUR' THEN b.net END) AS at_cur,
           SUM(CASE WHEN b.col = 'PM' THEN b.net END) AS at_pm,
           SUM(CASE WHEN b.col = 'PY' THEN b.net END) AS at_py
    FROM balances b
    GROUP BY b.code
),
-- Each account's amount in each column, debit positive.
acct_values AS (
    SELECT ac.code, ac.acct_label, ac.fin_type,
           CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'CUR') THEN COALESCE(pv.at_cur, 0) END AS amount_v,
           CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'PM') THEN COALESCE(pv.at_pm, 0) END AS priorMonth_v,
           CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'PY') THEN COALESCE(pv.at_py, 0) END AS priorYear_v
    FROM accts ac LEFT JOIN pivoted pv ON pv.code = ac.code
),
-- Whether each column has its cut-offs: evaluated for an income and a balance account with no amounts.
available AS (
    SELECT bool_and(ok.amount) AS amount, bool_and(ok.priorMonth) AS priorMonth, bool_and(ok.priorYear) AS priorYear
    FROM (SELECT (CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'CUR') THEN COALESCE(pv.at_cur, 0) END) IS NOT NULL AS amount,
           (CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'PM') THEN COALESCE(pv.at_pm, 0) END) IS NOT NULL AS priorMonth,
           (CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'PY') THEN COALESCE(pv.at_py, 0) END) IS NOT NULL AS priorYear
          FROM (VALUES ('REVENUE'), ('EQUITY')) AS ac (fin_type) LEFT JOIN pivoted pv ON false) ok
),
row_values AS (
    SELECT r.seq_no, r.line, r.caption, r.row_kind, r.each_account, r.skip_zero, r.note_ranges,
           CASE WHEN ok.amount THEN r.shown * COALESCE(SUM(av.amount_v), 0) END AS amount,
           CASE WHEN ok.priorMonth THEN r.shown * COALESCE(SUM(av.priorMonth_v), 0) END AS priorMonth,
           CASE WHEN ok.priorYear THEN r.shown * COALESCE(SUM(av.priorYear_v), 0) END AS priorYear
    FROM lrows r CROSS JOIN available ok
    LEFT JOIN matched m ON m.line = r.line LEFT JOIN acct_values av ON av.code = m.code
    WHERE r.row_kind <> 'HEADING'
    GROUP BY r.seq_no, r.line, r.caption, r.row_kind, r.each_account, r.skip_zero, r.note_ranges, r.shown,
             ok.amount, ok.priorMonth, ok.priorYear
),
note_values AS (
    SELECT n.line, ABS(SUM(av.amount_v)) AS note_amount
    FROM noted n JOIN acct_values av ON av.code = n.code
    GROUP BY n.line
),
out_rows AS (
    SELECT r.seq_no * 1000 AS seq, r.line AS lineCode, r.caption AS label, r.row_kind AS kind, CAST(NULL AS numeric) AS amount, CAST(NULL AS numeric) AS priorMonth, CAST(NULL AS numeric) AS priorYear
    FROM lrows r WHERE r.row_kind = 'HEADING'
    UNION ALL
    SELECT rv.seq_no * 1000, rv.line,
           CASE WHEN nv.note_amount IS NULL THEN rv.caption
                ELSE REPLACE(rv.caption, '{note}', TRIM(TO_CHAR(nv.note_amount, 'FM999,999,999,999,990.00'))) END,
           rv.row_kind, rv.amount, rv.priorMonth, rv.priorYear
    FROM row_values rv LEFT JOIN note_values nv ON nv.line = rv.line
    WHERE NOT rv.each_account AND (NOT rv.skip_zero OR COALESCE(rv.amount, 0) <> 0 OR COALESCE(rv.priorMonth, 0) <> 0 OR COALESCE(rv.priorYear, 0) <> 0)
    UNION ALL
    -- A line of each account: in code order, those with an amount in some column.
    SELECT r.seq_no * 1000 + ROW_NUMBER() OVER (PARTITION BY r.line ORDER BY av.code), r.line || '.' || av.code,
           av.acct_label, r.row_kind, r.shown * av.amount_v AS amount,
           r.shown * av.priorMonth_v AS priorMonth,
           r.shown * av.priorYear_v AS priorYear
    FROM lrows r JOIN matched m ON m.line = r.line JOIN acct_values av ON av.code = m.code
    WHERE r.each_account AND (COALESCE(av.amount_v, 0) <> 0 OR COALESCE(av.priorMonth_v, 0) <> 0 OR COALESCE(av.priorYear_v, 0) <> 0)
    UNION ALL
    -- Accounts with an amount on no line: the statement cannot be issued until the layout takes them.
    SELECT 100000000 + ROW_NUMBER() OVER (ORDER BY av.code), 'UNMAPPED.' || av.code,
           av.code || ' ' || av.acct_label, 'UNMAPPED', av.amount_v, av.priorMonth_v, av.priorYear_v
    FROM acct_values av
    WHERE (COALESCE(av.amount_v, 0) <> 0 OR COALESCE(av.priorMonth_v, 0) <> 0 OR COALESCE(av.priorYear_v, 0) <> 0)
      AND NOT EXISTS (SELECT 1 FROM matched m JOIN lrows r ON r.line = m.line
                      WHERE m.code = av.code AND r.row_kind = 'LINE')
)
SELECT seq, lineCode, label, kind, amount, priorMonth, priorYear FROM out_rows
