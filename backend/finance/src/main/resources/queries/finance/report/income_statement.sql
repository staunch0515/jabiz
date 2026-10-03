/*---
id: finance.report.income_statement
description: >-
  The multi-step income statement (FIN-RP-003; FIN-EXP-04): each line of the layout for the month holding the last
  day, the fiscal quarter and the fiscal year to that day, and the same month and year to date a year before; a line
  per account where the layout asks; for one department or location if asked. Income positive, expenses in
  parentheses (the layout's signs). Closing entries never count; period 13 counts where its days fall unless left out.
  At the end any income or expense account with an amount in some column on no line of the layout (unmapped). A
  column whose last day is in no period (a year before the books) is left empty. Read from the period balances.
entities: [FinStatementLayout, FinStatementLayoutRow, LedgerAccount, LedgerTransaction, LedgerEntry, FinAccount,
  FinPosting, FinPeriod, FinPeriodBalance]
params:
  through:       { like: FinPosting.postingDate, required: true, description: the last day }
  adjustments:   { kind: { type: bool }, description: "whether period 13 counts where its days fall; yes when not given" }
  department:    { like: LedgerEntry.dimension1, description: "only this department's entries" }
  location:      { like: LedgerEntry.dimension2, description: "only this location's entries" }
  layout:        { like: FinStatementLayout.layoutCode, description: "the layout's code; IS when not given" }
  layoutVersion: { like: FinStatementLayout.version, description: "the layout's version; the latest when not given" }
  knownAt:       { like: LedgerTransaction.bookingTime, description: "if given, the books as recorded at this time" }
results:
  seq:      { kind: { type: numeric, precision: 9, scale: 0 } }
  lineCode: { kind: { type: text, maxLength: 60 } }
  label:    { kind: { type: text, maxLength: 300 } }
  kind:     { kind: { type: text, maxLength: 10 } }
  month: { from: LedgerEntry.amount }
  quarter: { from: LedgerEntry.amount }
  yearToDate: { from: LedgerEntry.amount }
  priorMonth: { from: LedgerEntry.amount }
  priorYearToDate: { from: LedgerEntry.amount }
list:
  filters: [lineCode, kind]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [lineCode]
permissions: [ledger.read]
timeSlice: { knownAt: knownAt }
report:
  period: { to: through }
  landscape: true
---*/
WITH here AS (
    SELECT p.{{FinPeriod.startDate}} AS month_start, p.{{FinPeriod.fiscalYear}} AS fy, p.{{FinPeriod.periodNo}} AS pno
    FROM {{FinPeriod}} p
    WHERE p.{{FinPeriod.startDate}} <= :through AND p.{{FinPeriod.endDate}} >= :through
      AND NOT COALESCE(p.{{FinPeriod.adjustment}}, false) AND NOT COALESCE(p.{{FinPeriod.opening}}, false)
    ORDER BY p.{{FinPeriod.startDate}} DESC
    LIMIT 1
),
starts AS (
    SELECT h.month_start,
           (SELECT q.{{FinPeriod.startDate}} FROM {{FinPeriod}} q
            WHERE q.{{FinPeriod.fiscalYear}} = h.fy AND NOT COALESCE(q.{{FinPeriod.adjustment}}, false)
              AND q.{{FinPeriod.periodNo}} = FLOOR((h.pno - 1) / 3) * 3 + 1) AS quarter_start,
           (SELECT MIN(y.{{FinPeriod.startDate}}) FROM {{FinPeriod}} y
            WHERE y.{{FinPeriod.fiscalYear}} = h.fy AND NOT COALESCE(y.{{FinPeriod.opening}}, false)) AS year_start
    FROM here h
),
cutoffs (col, day) AS (
    SELECT 'B', CAST(:through AS date)
    UNION ALL SELECT 'M', CAST(s.month_start - 1 AS date) FROM starts s
    UNION ALL SELECT 'Q', CAST(s.quarter_start - 1 AS date) FROM starts s
    UNION ALL SELECT 'Y', CAST(s.year_start - 1 AS date) FROM starts s
    UNION ALL SELECT 'BP', CAST(CAST(:through AS date) - INTERVAL '1 year' AS date)
    UNION ALL SELECT 'MP', CAST(s.month_start - INTERVAL '1 year' AS date) - 1 FROM starts s
    UNION ALL SELECT 'YP', CAST(s.year_start - INTERVAL '1 year' AS date) - 1 FROM starts s
),
-- The layout version: the one asked for, else the latest; a layout of another statement gives nothing.
lay AS (
    SELECT l.{{FinStatementLayout.layoutId}} AS layout_key
    FROM {{FinStatementLayout}} l
    WHERE l.{{FinStatementLayout.layoutCode}} = COALESCE(CAST(:layout AS text), 'IS')
      AND l.{{FinStatementLayout.statement}} = 'INCOME_STATEMENT'
      AND l.{{FinStatementLayout.version}} = COALESCE(CAST(:layoutVersion AS numeric), (
          SELECT MAX(m.{{FinStatementLayout.version}}) FROM {{FinStatementLayout}} m
          WHERE m.{{FinStatementLayout.layoutCode}} = COALESCE(CAST(:layout AS text), 'IS')))
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
    WHERE COALESCE(CAST(:adjustments AS boolean), true)
       OR NOT COALESCE(p.{{FinPeriod.adjustment}}, false)
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
      AND (CAST(:department AS text) IS NULL OR e.{{LedgerEntry.dimension1}} = :department)
      AND (CAST(:location AS text) IS NULL OR e.{{LedgerEntry.dimension2}} = :location)
),
period_net AS (
    SELECT s.{{FinPeriodBalance.periodKey}} AS pkey, s.{{FinPeriodBalance.accountCode}} AS code,
           s.{{FinPeriodBalance.debit}} - s.{{FinPeriodBalance.credit}} AS net
    FROM {{FinPeriodBalance}} s
    JOIN snapshots sn ON sn.pkey = s.{{FinPeriodBalance.periodKey}} AND sn.counted = s.{{FinPeriodBalance.countedTo}}
    WHERE EXISTS (SELECT 1 FROM lay)
      AND (CAST(:department AS text) IS NULL OR s.{{FinPeriodBalance.department}} = :department)
      AND (CAST(:location AS text) IS NULL OR s.{{FinPeriodBalance.location}} = :location)
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
           SUM(CASE WHEN b.col = 'B' THEN b.net END) AS at_b,
           SUM(CASE WHEN b.col = 'M' THEN b.net END) AS at_m,
           SUM(CASE WHEN b.col = 'Q' THEN b.net END) AS at_q,
           SUM(CASE WHEN b.col = 'Y' THEN b.net END) AS at_y,
           SUM(CASE WHEN b.col = 'BP' THEN b.net END) AS at_bp,
           SUM(CASE WHEN b.col = 'MP' THEN b.net END) AS at_mp,
           SUM(CASE WHEN b.col = 'YP' THEN b.net END) AS at_yp
    FROM balances b
    GROUP BY b.code
),
-- Each account's amount in each column, debit positive.
acct_values AS (
    SELECT ac.code, ac.acct_label, ac.fin_type,
           CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'B') THEN COALESCE(pv.at_b, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'M' AND c.day IS NOT NULL) THEN COALESCE(pv.at_m, 0) END AS month_v,
           CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'B') THEN COALESCE(pv.at_b, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'Q' AND c.day IS NOT NULL) THEN COALESCE(pv.at_q, 0) END AS quarter_v,
           CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'B') THEN COALESCE(pv.at_b, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'Y' AND c.day IS NOT NULL) THEN COALESCE(pv.at_y, 0) END AS yearToDate_v,
           CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'BP') THEN COALESCE(pv.at_bp, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'MP' AND c.day IS NOT NULL) THEN COALESCE(pv.at_mp, 0) END AS priorMonth_v,
           CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'BP') THEN COALESCE(pv.at_bp, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'YP' AND c.day IS NOT NULL) THEN COALESCE(pv.at_yp, 0) END AS priorYearToDate_v
    FROM accts ac LEFT JOIN pivoted pv ON pv.code = ac.code
),
-- Whether each column has its cut-offs: evaluated for an income and a balance account with no amounts.
available AS (
    SELECT bool_and(ok.month) AS month, bool_and(ok.quarter) AS quarter, bool_and(ok.yearToDate) AS yearToDate, bool_and(ok.priorMonth) AS priorMonth, bool_and(ok.priorYearToDate) AS priorYearToDate
    FROM (SELECT (CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'B') THEN COALESCE(pv.at_b, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'M' AND c.day IS NOT NULL) THEN COALESCE(pv.at_m, 0) END) IS NOT NULL AS month,
           (CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'B') THEN COALESCE(pv.at_b, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'Q' AND c.day IS NOT NULL) THEN COALESCE(pv.at_q, 0) END) IS NOT NULL AS quarter,
           (CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'B') THEN COALESCE(pv.at_b, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'Y' AND c.day IS NOT NULL) THEN COALESCE(pv.at_y, 0) END) IS NOT NULL AS yearToDate,
           (CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'BP') THEN COALESCE(pv.at_bp, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'MP' AND c.day IS NOT NULL) THEN COALESCE(pv.at_mp, 0) END) IS NOT NULL AS priorMonth,
           (CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'BP') THEN COALESCE(pv.at_bp, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'YP' AND c.day IS NOT NULL) THEN COALESCE(pv.at_yp, 0) END) IS NOT NULL AS priorYearToDate
          FROM (VALUES ('REVENUE'), ('EQUITY')) AS ac (fin_type) LEFT JOIN pivoted pv ON false) ok
),
row_values AS (
    SELECT r.seq_no, r.line, r.caption, r.row_kind, r.each_account, r.skip_zero, r.note_ranges,
           CASE WHEN ok.month THEN r.shown * COALESCE(SUM(av.month_v), 0) END AS month,
           CASE WHEN ok.quarter THEN r.shown * COALESCE(SUM(av.quarter_v), 0) END AS quarter,
           CASE WHEN ok.yearToDate THEN r.shown * COALESCE(SUM(av.yearToDate_v), 0) END AS yearToDate,
           CASE WHEN ok.priorMonth THEN r.shown * COALESCE(SUM(av.priorMonth_v), 0) END AS priorMonth,
           CASE WHEN ok.priorYearToDate THEN r.shown * COALESCE(SUM(av.priorYearToDate_v), 0) END AS priorYearToDate
    FROM lrows r CROSS JOIN available ok
    LEFT JOIN matched m ON m.line = r.line LEFT JOIN acct_values av ON av.code = m.code
    WHERE r.row_kind <> 'HEADING'
    GROUP BY r.seq_no, r.line, r.caption, r.row_kind, r.each_account, r.skip_zero, r.note_ranges, r.shown,
             ok.month, ok.quarter, ok.yearToDate, ok.priorMonth, ok.priorYearToDate
),
note_values AS (
    SELECT n.line, ABS(SUM(av.yearToDate_v)) AS note_amount
    FROM noted n JOIN acct_values av ON av.code = n.code
    GROUP BY n.line
),
out_rows AS (
    SELECT r.seq_no * 1000 AS seq, r.line AS lineCode, r.caption AS label, r.row_kind AS kind, CAST(NULL AS numeric) AS month, CAST(NULL AS numeric) AS quarter, CAST(NULL AS numeric) AS yearToDate, CAST(NULL AS numeric) AS priorMonth, CAST(NULL AS numeric) AS priorYearToDate
    FROM lrows r WHERE r.row_kind = 'HEADING'
    UNION ALL
    SELECT rv.seq_no * 1000, rv.line,
           CASE WHEN nv.note_amount IS NULL THEN rv.caption
                ELSE REPLACE(rv.caption, '{note}', TRIM(TO_CHAR(nv.note_amount, 'FM999,999,999,999,990.00'))) END,
           rv.row_kind, rv.month, rv.quarter, rv.yearToDate, rv.priorMonth, rv.priorYearToDate
    FROM row_values rv LEFT JOIN note_values nv ON nv.line = rv.line
    WHERE NOT rv.each_account AND (NOT rv.skip_zero OR COALESCE(rv.month, 0) <> 0 OR COALESCE(rv.quarter, 0) <> 0 OR COALESCE(rv.yearToDate, 0) <> 0 OR COALESCE(rv.priorMonth, 0) <> 0 OR COALESCE(rv.priorYearToDate, 0) <> 0)
    UNION ALL
    -- A line of each account: in code order, those with an amount in some column.
    SELECT r.seq_no * 1000 + ROW_NUMBER() OVER (PARTITION BY r.line ORDER BY av.code), r.line || '.' || av.code,
           av.acct_label, r.row_kind, r.shown * av.month_v AS month,
           r.shown * av.quarter_v AS quarter,
           r.shown * av.yearToDate_v AS yearToDate,
           r.shown * av.priorMonth_v AS priorMonth,
           r.shown * av.priorYearToDate_v AS priorYearToDate
    FROM lrows r JOIN matched m ON m.line = r.line JOIN acct_values av ON av.code = m.code
    WHERE r.each_account AND (COALESCE(av.month_v, 0) <> 0 OR COALESCE(av.quarter_v, 0) <> 0 OR COALESCE(av.yearToDate_v, 0) <> 0 OR COALESCE(av.priorMonth_v, 0) <> 0 OR COALESCE(av.priorYearToDate_v, 0) <> 0)
    UNION ALL
    -- Accounts with an amount on no line: the statement cannot be issued until the layout takes them.
    SELECT 100000000 + ROW_NUMBER() OVER (ORDER BY av.code), 'UNMAPPED.' || av.code,
           av.code || ' ' || av.acct_label, 'UNMAPPED', av.month_v, av.quarter_v, av.yearToDate_v, av.priorMonth_v, av.priorYearToDate_v
    FROM acct_values av
    WHERE av.fin_type IN ('REVENUE', 'EXPENSE', 'TAX', 'OTHER') AND (COALESCE(av.month_v, 0) <> 0 OR COALESCE(av.quarter_v, 0) <> 0 OR COALESCE(av.yearToDate_v, 0) <> 0 OR COALESCE(av.priorMonth_v, 0) <> 0 OR COALESCE(av.priorYearToDate_v, 0) <> 0)
      AND NOT EXISTS (SELECT 1 FROM matched m JOIN lrows r ON r.line = m.line
                      WHERE m.code = av.code AND r.row_kind = 'LINE')
)
SELECT seq, lineCode, label, kind, month, quarter, yearToDate, priorMonth, priorYearToDate FROM out_rows
