/*---
id: finance.report.equity
description: >-
  The statement of changes in stockholders' equity (FIN-RP-005; FIN-EXP-07) over a range, one row per component (a
  line of the layout) and its total: the balance before the range, the net income of the range (the income and
  expense accounts the component takes), the other changes (its equity accounts: dividends, issues, other) and the
  balance at its end. The range starts at the fiscal year's start unless given. Closing entries never count. At the
  end any equity account with a balance, or income or expense account with an amount, on no line of the layout
  (unmapped). With a last day in no period the statement is empty. Read from the period balances.
entities: [FinStatementLayout, FinStatementLayoutRow, LedgerAccount, LedgerTransaction, LedgerEntry, FinAccount,
  FinPosting, FinPeriod, FinPeriodBalance]
params:
  from:          { like: FinPosting.postingDate, description: "the range's first day; the fiscal year's start when not given" }
  through:       { like: FinPosting.postingDate, required: true, description: the range's last day }
  layout:        { like: FinStatementLayout.layoutCode, description: "the layout's code; EQ when not given" }
  layoutVersion: { like: FinStatementLayout.version, description: "the layout's version; the latest when not given" }
  knownAt:       { like: LedgerTransaction.bookingTime, description: "if given, the books as recorded at this time" }
results:
  seq:      { kind: { type: numeric, precision: 9, scale: 0 } }
  lineCode: { kind: { type: text, maxLength: 60 } }
  label:    { kind: { type: text, maxLength: 300 } }
  kind:     { kind: { type: text, maxLength: 10 } }
  opening: { from: LedgerEntry.amount }
  netIncome: { from: LedgerEntry.amount }
  otherChanges: { from: LedgerEntry.amount }
  closing: { from: LedgerEntry.amount }
list:
  filters: [lineCode, kind]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [lineCode]
permissions: [ledger.read]
timeSlice: { knownAt: knownAt }
report:
  period: { from: from, to: through }
---*/
WITH cutoffs (col, day) AS (
    SELECT 'E', CAST(:through AS date)
    UNION ALL
    SELECT 'S', CAST(COALESCE(CAST(:from AS date), (
        SELECT MIN(y.{{FinPeriod.startDate}}) FROM {{FinPeriod}} y
        WHERE NOT COALESCE(y.{{FinPeriod.opening}}, false) AND y.{{FinPeriod.fiscalYear}} = (
            SELECT MAX(h.{{FinPeriod.fiscalYear}}) FROM {{FinPeriod}} h
            WHERE h.{{FinPeriod.startDate}} <= :through AND h.{{FinPeriod.endDate}} >= :through))) - 1 AS date)
),
-- The layout version: the one asked for, else the latest; a layout of another statement gives nothing.
lay AS (
    SELECT l.{{FinStatementLayout.layoutId}} AS layout_key
    FROM {{FinStatementLayout}} l
    WHERE l.{{FinStatementLayout.layoutCode}} = COALESCE(CAST(:layout AS text), 'EQ')
      AND l.{{FinStatementLayout.statement}} = 'EQUITY'
      AND l.{{FinStatementLayout.version}} = COALESCE(CAST(:layoutVersion AS numeric), (
          SELECT MAX(m.{{FinStatementLayout.version}}) FROM {{FinStatementLayout}} m
          WHERE m.{{FinStatementLayout.layoutCode}} = COALESCE(CAST(:layout AS text), 'EQ')))
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
           SUM(CASE WHEN b.col = 'E' THEN b.net END) AS at_e,
           SUM(CASE WHEN b.col = 'S' THEN b.net END) AS at_s
    FROM balances b
    GROUP BY b.code
),
-- Each account's amount in each column, debit positive.
acct_values AS (
    SELECT ac.code, ac.acct_label, ac.fin_type,
           CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'S' AND c.day IS NOT NULL) THEN COALESCE(pv.at_s, 0) END AS opening_v,
           CASE WHEN ac.fin_type IN ('REVENUE', 'EXPENSE', 'TAX', 'OTHER') THEN CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'E') THEN COALESCE(pv.at_e, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'S' AND c.day IS NOT NULL) THEN COALESCE(pv.at_s, 0) END ELSE 0 END AS netIncome_v,
           CASE WHEN ac.fin_type IN ('REVENUE', 'EXPENSE', 'TAX', 'OTHER') THEN 0 ELSE CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'E') THEN COALESCE(pv.at_e, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'S' AND c.day IS NOT NULL) THEN COALESCE(pv.at_s, 0) END END AS otherChanges_v,
           CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'E') THEN COALESCE(pv.at_e, 0) END AS closing_v
    FROM accts ac LEFT JOIN pivoted pv ON pv.code = ac.code
),
-- Whether each column has its cut-offs: evaluated for an income and a balance account with no amounts.
available AS (
    SELECT bool_and(ok.opening) AS opening, bool_and(ok.netIncome) AS netIncome, bool_and(ok.otherChanges) AS otherChanges, bool_and(ok.closing) AS closing
    FROM (SELECT (CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'S' AND c.day IS NOT NULL) THEN COALESCE(pv.at_s, 0) END) IS NOT NULL AS opening,
           (CASE WHEN ac.fin_type IN ('REVENUE', 'EXPENSE', 'TAX', 'OTHER') THEN CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'E') THEN COALESCE(pv.at_e, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'S' AND c.day IS NOT NULL) THEN COALESCE(pv.at_s, 0) END ELSE 0 END) IS NOT NULL AS netIncome,
           (CASE WHEN ac.fin_type IN ('REVENUE', 'EXPENSE', 'TAX', 'OTHER') THEN 0 ELSE CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'E') THEN COALESCE(pv.at_e, 0) END - CASE WHEN EXISTS (SELECT 1 FROM cutoffs c WHERE c.col = 'S' AND c.day IS NOT NULL) THEN COALESCE(pv.at_s, 0) END END) IS NOT NULL AS otherChanges,
           (CASE WHEN EXISTS (SELECT 1 FROM cutoffs c JOIN {{FinPeriod}} cp ON c.day BETWEEN cp.{{FinPeriod.startDate}} AND cp.{{FinPeriod.endDate}} WHERE c.col = 'E') THEN COALESCE(pv.at_e, 0) END) IS NOT NULL AS closing
          FROM (VALUES ('REVENUE'), ('EQUITY')) AS ac (fin_type) LEFT JOIN pivoted pv ON false) ok
),
row_values AS (
    SELECT r.seq_no, r.line, r.caption, r.row_kind, r.each_account, r.skip_zero, r.note_ranges,
           CASE WHEN ok.opening THEN r.shown * COALESCE(SUM(av.opening_v), 0) END AS opening,
           CASE WHEN ok.netIncome THEN r.shown * COALESCE(SUM(av.netIncome_v), 0) END AS netIncome,
           CASE WHEN ok.otherChanges THEN r.shown * COALESCE(SUM(av.otherChanges_v), 0) END AS otherChanges,
           CASE WHEN ok.closing THEN r.shown * COALESCE(SUM(av.closing_v), 0) END AS closing
    FROM lrows r CROSS JOIN available ok
    LEFT JOIN matched m ON m.line = r.line LEFT JOIN acct_values av ON av.code = m.code
    WHERE r.row_kind <> 'HEADING'
    GROUP BY r.seq_no, r.line, r.caption, r.row_kind, r.each_account, r.skip_zero, r.note_ranges, r.shown,
             ok.opening, ok.netIncome, ok.otherChanges, ok.closing
),
note_values AS (
    SELECT n.line, ABS(SUM(av.closing_v)) AS note_amount
    FROM noted n JOIN acct_values av ON av.code = n.code
    GROUP BY n.line
),
out_rows AS (
    SELECT r.seq_no * 1000 AS seq, r.line AS lineCode, r.caption AS label, r.row_kind AS kind, CAST(NULL AS numeric) AS opening, CAST(NULL AS numeric) AS netIncome, CAST(NULL AS numeric) AS otherChanges, CAST(NULL AS numeric) AS closing
    FROM lrows r WHERE r.row_kind = 'HEADING'
    UNION ALL
    SELECT rv.seq_no * 1000, rv.line,
           CASE WHEN nv.note_amount IS NULL THEN rv.caption
                ELSE REPLACE(rv.caption, '{note}', TRIM(TO_CHAR(nv.note_amount, 'FM999,999,999,999,990.00'))) END,
           rv.row_kind, rv.opening, rv.netIncome, rv.otherChanges, rv.closing
    FROM row_values rv LEFT JOIN note_values nv ON nv.line = rv.line
    WHERE NOT rv.each_account AND (NOT rv.skip_zero OR COALESCE(rv.opening, 0) <> 0 OR COALESCE(rv.netIncome, 0) <> 0 OR COALESCE(rv.otherChanges, 0) <> 0 OR COALESCE(rv.closing, 0) <> 0)
    UNION ALL
    -- A line of each account: in code order, those with an amount in some column.
    SELECT r.seq_no * 1000 + ROW_NUMBER() OVER (PARTITION BY r.line ORDER BY av.code), r.line || '.' || av.code,
           av.acct_label, r.row_kind, r.shown * av.opening_v AS opening,
           r.shown * av.netIncome_v AS netIncome,
           r.shown * av.otherChanges_v AS otherChanges,
           r.shown * av.closing_v AS closing
    FROM lrows r JOIN matched m ON m.line = r.line JOIN acct_values av ON av.code = m.code
    WHERE r.each_account AND (COALESCE(av.opening_v, 0) <> 0 OR COALESCE(av.netIncome_v, 0) <> 0 OR COALESCE(av.otherChanges_v, 0) <> 0 OR COALESCE(av.closing_v, 0) <> 0)
    UNION ALL
    -- Accounts with an amount on no line: the statement cannot be issued until the layout takes them.
    SELECT 100000000 + ROW_NUMBER() OVER (ORDER BY av.code), 'UNMAPPED.' || av.code,
           av.code || ' ' || av.acct_label, 'UNMAPPED', av.opening_v, av.netIncome_v, av.otherChanges_v, av.closing_v
    FROM acct_values av
    WHERE ((av.fin_type = 'EQUITY' AND (COALESCE(av.opening_v, 0) <> 0 OR COALESCE(av.closing_v, 0) <> 0)) OR (av.fin_type IN ('REVENUE', 'EXPENSE', 'TAX', 'OTHER') AND COALESCE(av.netIncome_v, 0) <> 0))
      AND NOT EXISTS (SELECT 1 FROM matched m JOIN lrows r ON r.line = m.line
                      WHERE m.code = av.code AND r.row_kind = 'LINE')
)
SELECT seq, lineCode, label, kind, opening, netIncome, otherChanges, closing FROM out_rows
