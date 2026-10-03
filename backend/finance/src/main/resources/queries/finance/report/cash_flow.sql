/*---
id: finance.report.cash_flow
description: >-
  The statement of cash flows over a range by the indirect method (FIN-RP-004; FIN-EXP-06; ROADMAP F9 decision D5).
  Operating: the net income, the non-cash items found from their sources (depreciation runs, foreign-currency
  revaluations and their reversals) and the change in each statement line of the operating accounts; investing and
  financing: the change in each statement line of their accounts. Each account's cash flow class says where it goes
  (CASH for cash and cash equivalents). A posting that moves no cash but an investing or financing account (an asset
  bought on account) is left out of both sides and shown as a non-cash activity. Then the effect of exchange rates on
  cash (revalued bank accounts), the net change in cash, cash at
  the start and the end, the unexplained difference (zero unless an account with a change has no class, listed as
  UNCLASSIFIED: FIN_CASH_FLOW_ISSUE refuses to issue it) and the interest and income taxes paid from the report
  settings' accounts (expense less the increase in the payable). Amounts are cash in positive. The range starts at the
  regular period holding the last day unless given; period 13 of that day's fiscal year counts unless left out;
  closing entries never count. Read from the period balances.
entities: [LedgerAccount, LedgerTransaction, LedgerEntry, FinAccount, FinPosting, FinPeriod, FinPeriodBalance,
  FinReportSettings]
params:
  from:        { like: FinPosting.postingDate, description: "the range's first day; the start of the regular period holding the last day when not given" }
  through:     { like: FinPosting.postingDate, required: true, description: the range's last day }
  adjustments: { kind: { type: bool }, description: "whether period 13 of the last day's fiscal year counts; yes when not given" }
  knownAt:     { like: LedgerTransaction.bookingTime, description: "if given, the books as recorded at this time" }
results:
  seq:      { kind: { type: numeric, precision: 9, scale: 0 } }
  lineCode: { kind: { type: text, maxLength: 60 } }
  label:    { kind: { type: text, maxLength: 300 } }
  kind:     { kind: { type: text, maxLength: 12 } }
  amount:   { from: LedgerEntry.amount }
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
WITH bounds AS (
    SELECT COALESCE(CAST(:from AS date),
               (SELECT MAX(rp.{{FinPeriod.startDate}}) FROM {{FinPeriod}} rp
                WHERE rp.{{FinPeriod.startDate}} <= :through AND rp.{{FinPeriod.endDate}} >= :through
                  AND NOT COALESCE(rp.{{FinPeriod.adjustment}}, false)),
               CAST(:through AS date)) AS first_day,
           (SELECT MAX(yp.{{FinPeriod.fiscalYear}}) FROM {{FinPeriod}} yp
            WHERE yp.{{FinPeriod.startDate}} <= :through AND yp.{{FinPeriod.endDate}} >= :through) AS last_year
),
-- Each period that may hold counted entries: wholly before the range ('O') or inside it ('R'), else partly so (read
-- entry by entry); period 13 is read entry by entry without its closing entries, and the last year's is left out if
-- asked.
periods AS (
    SELECT p.{{FinPeriod.periodKey}} AS pkey,
           CASE WHEN p.{{FinPeriod.endDate}} < b.first_day THEN 'O'
                WHEN p.{{FinPeriod.startDate}} >= b.first_day AND p.{{FinPeriod.endDate}} <= :through THEN 'R'
           END AS whole,
           COALESCE(p.{{FinPeriod.adjustment}}, false) AS adj,
           COALESCE(p.{{FinPeriod.adjustment}}, false) AND p.{{FinPeriod.fiscalYear}} = b.last_year
               AND NOT COALESCE(CAST(:adjustments AS boolean), true) AS left_out
    FROM {{FinPeriod}} p CROSS JOIN bounds b
    WHERE p.{{FinPeriod.startDate}} <= :through
),
snapshots AS (
    SELECT s.{{FinPeriodBalance.periodKey}} AS pkey, MAX(s.{{FinPeriodBalance.countedTo}}) AS counted
    FROM {{FinPeriodBalance}} s
    JOIN periods pr ON pr.pkey = s.{{FinPeriodBalance.periodKey}}
    WHERE pr.whole IS NOT NULL AND NOT pr.adj
      AND (CAST(:knownAt AS timestamptz) IS NULL OR s.{{FinPeriodBalance.countedTo}} <= :knownAt)
    GROUP BY s.{{FinPeriodBalance.periodKey}}
),
-- Net amounts (debit positive) before the range ('O') and in it ('R').
moves AS (
    SELECT a.{{LedgerAccount.accountId}} AS acct_key, pr.whole AS side,
           s.{{FinPeriodBalance.debit}} - s.{{FinPeriodBalance.credit}} AS net
    FROM {{FinPeriodBalance}} s
    JOIN snapshots sn ON sn.pkey = s.{{FinPeriodBalance.periodKey}} AND sn.counted = s.{{FinPeriodBalance.countedTo}}
    JOIN periods pr ON pr.pkey = s.{{FinPeriodBalance.periodKey}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountCode}} = s.{{FinPeriodBalance.accountCode}}
    UNION ALL
    SELECT e.{{LedgerEntry.accountId}},
           CASE WHEN fp.{{FinPosting.postingDate}} < b.first_day THEN 'O' ELSE 'R' END,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN periods pr ON pr.pkey = fp.{{FinPosting.periodKey}}
    CROSS JOIN bounds b
    LEFT JOIN snapshots sn ON sn.pkey = fp.{{FinPosting.periodKey}}
    WHERE fp.{{FinPosting.postingDate}} <= :through
      AND NOT pr.left_out
      -- CLS: the general ledger source of the year-end closing entries (JournalEntities.postingSource).
      AND fp.{{FinPosting.source}} <> 'CLS'
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
      AND (sn.counted IS NULL OR t.{{LedgerTransaction.createdTime}} > sn.counted)
),
accts AS (
    SELECT a.{{LedgerAccount.accountId}} AS acct_key, a.{{LedgerAccount.accountCode}} AS code,
           f.{{FinAccount.financialType}} IN ('REVENUE', 'EXPENSE', 'TAX', 'OTHER') AS income,
           f.{{FinAccount.cashFlowClass}} AS cf_class,
           COALESCE(f.{{FinAccount.statementLine}}, a.{{LedgerAccount.accountName}}) AS stmt_line,
           a.{{LedgerAccount.accountName}} AS acct_name
    FROM {{LedgerAccount}} a
    JOIN {{FinAccount}} f ON f.{{FinAccount.ledgerAccountId}} = a.{{LedgerAccount.accountId}}
    WHERE NOT COALESCE(a.{{LedgerAccount.summary}}, false)
),
-- The range's postings of non-cash items: depreciation runs (DEP), foreign-currency revaluations and their reversals
-- (FXR), and postings that move an investing or financing account and no cash (NONCASH).
range_postings AS (
    SELECT fp.{{FinPosting.transactionId}} AS tx,
           CASE WHEN fp.{{FinPosting.sourceEntity}} = 'FinDepreciationRun' THEN 'DEP'
                WHEN fp.{{FinPosting.sourceEntity}} = 'FinFxRevaluationRun' THEN 'FXR' END AS tag
    FROM {{FinPosting}} fp
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = fp.{{FinPosting.transactionId}}
    JOIN periods pr ON pr.pkey = fp.{{FinPosting.periodKey}}
    CROSS JOIN bounds b
    WHERE fp.{{FinPosting.postingDate}} BETWEEN b.first_day AND :through
      AND NOT pr.left_out AND fp.{{FinPosting.source}} NOT IN ('CLS', 'OPN')
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
      AND (fp.{{FinPosting.sourceEntity}} IN ('FinDepreciationRun', 'FinFxRevaluationRun')
           OR EXISTS (SELECT 1 FROM {{LedgerEntry}} ie JOIN accts ia ON ia.acct_key = ie.{{LedgerEntry.accountId}}
                      WHERE ie.{{LedgerEntry.transactionId}} = fp.{{FinPosting.transactionId}}
                        AND ia.cf_class IN ('INVESTING', 'FINANCING')))
),
tagged_postings AS (
    SELECT rp.tx, COALESCE(rp.tag, 'NONCASH') AS tag
    FROM range_postings rp
    WHERE rp.tag IS NOT NULL
       OR NOT EXISTS (SELECT 1 FROM {{LedgerEntry}} ce JOIN accts ca ON ca.acct_key = ce.{{LedgerEntry.accountId}}
                      WHERE ce.{{LedgerEntry.transactionId}} = rp.tx AND ca.cf_class = 'CASH')
),
tagged_entries AS (
    SELECT tp.tx, tp.tag, e.{{LedgerEntry.accountId}} AS acct_key,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END AS net
    FROM tagged_postings tp
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = tp.tx
),
-- A non-cash posting's investing and financing side, and as much of its largest operating entry of the other sign:
-- the asset and the payable it was bought on. Only when that entry takes it all: else (an asset written off against a
-- loss, debt forgiven as income) the posting stays in the sections as booked, its income side in the net income.
noncash_total AS (
    SELECT te.tx, SUM(te.net) AS net
    FROM tagged_entries te JOIN accts a ON a.acct_key = te.acct_key
    WHERE te.tag = 'NONCASH' AND a.cf_class IN ('INVESTING', 'FINANCING')
    GROUP BY te.tx
),
noncash_offset AS (
    SELECT DISTINCT ON (te.tx) te.tx, te.acct_key, -nt.net AS net
    FROM tagged_entries te
    JOIN accts a ON a.acct_key = te.acct_key
    JOIN noncash_total nt ON nt.tx = te.tx
    WHERE te.tag = 'NONCASH' AND NOT a.income AND COALESCE(a.cf_class, '') NOT IN ('INVESTING', 'FINANCING', 'CASH')
      AND SIGN(te.net) = -SIGN(nt.net) AND ABS(te.net) >= ABS(nt.net)
    ORDER BY te.tx, ABS(te.net) DESC, a.code
),
-- What is left out of each account's change, and what goes to the non-cash lines.
excluded AS (
    SELECT te.acct_key, te.tag, te.net
    FROM tagged_entries te JOIN accts a ON a.acct_key = te.acct_key
    WHERE te.tag IN ('DEP', 'FXR') AND NOT a.income
    UNION ALL
    SELECT te.acct_key, te.tag, te.net
    FROM tagged_entries te JOIN accts a ON a.acct_key = te.acct_key
    WHERE te.tag = 'NONCASH' AND a.cf_class IN ('INVESTING', 'FINANCING')
      AND EXISTS (SELECT 1 FROM noncash_offset o WHERE o.tx = te.tx)
    UNION ALL
    SELECT o.acct_key, 'NONCASH', o.net FROM noncash_offset o
),
changes AS (
    SELECT a.acct_key, a.code, a.income, a.cf_class, a.stmt_line, a.acct_name,
           COALESCE((SELECT SUM(m.net) FROM moves m WHERE m.acct_key = a.acct_key AND m.side = 'O'), 0) AS before_net,
           COALESCE((SELECT SUM(m.net) FROM moves m WHERE m.acct_key = a.acct_key AND m.side = 'R'), 0) AS range_net,
           COALESCE((SELECT SUM(x.net) FROM excluded x WHERE x.acct_key = a.acct_key), 0) AS left_out
    FROM accts a
),
addbacks AS (
    SELECT te.tag, SUM(te.net) AS net
    FROM tagged_entries te JOIN accts a ON a.acct_key = te.acct_key
    WHERE te.tag IN ('DEP', 'FXR') AND a.income
    GROUP BY te.tag
),
-- Cash in positive: the opposite of each account's change, less what was left out.
section_lines AS (
    SELECT c.cf_class AS section, MIN(c.code) AS first_code, c.stmt_line,
           -SUM(c.range_net - c.left_out) AS amount
    FROM changes c
    WHERE NOT c.income AND c.cf_class IN ('OPERATING', 'INVESTING', 'FINANCING')
    GROUP BY c.cf_class, c.stmt_line
),
unclassified AS (
    SELECT c.code, c.acct_name, -(c.range_net - c.left_out) AS amount
    FROM changes c
    WHERE NOT c.income AND c.cf_class IS NULL AND c.range_net - c.left_out <> 0
),
figures AS (
    SELECT -COALESCE((SELECT SUM(c.range_net) FROM changes c WHERE c.income), 0) AS net_income,
           COALESCE((SELECT SUM(ab.net) FROM addbacks ab), 0) AS addback,
           COALESCE((SELECT SUM(sl.amount) FROM section_lines sl WHERE sl.section = 'OPERATING'), 0) AS operating_wc,
           COALESCE((SELECT SUM(sl.amount) FROM section_lines sl WHERE sl.section = 'INVESTING'), 0) AS investing,
           COALESCE((SELECT SUM(sl.amount) FROM section_lines sl WHERE sl.section = 'FINANCING'), 0) AS financing,
           COALESCE((SELECT SUM(c.range_net) FROM changes c WHERE c.cf_class = 'CASH'), 0) AS cash_change,
           -- A revaluation of a foreign-currency bank account: the effect of exchange rates on cash.
           COALESCE((SELECT SUM(c.left_out) FROM changes c WHERE c.cf_class = 'CASH'), 0) AS cash_fx,
           COALESCE((SELECT SUM(c.before_net) FROM changes c WHERE c.cf_class = 'CASH'), 0) AS cash_opening
),
settings AS (
    SELECT rs.{{FinReportSettings.interestAccounts}} AS interest,
           rs.{{FinReportSettings.interestPayableAccounts}} AS interest_payable,
           rs.{{FinReportSettings.incomeTaxAccounts}} AS income_tax,
           rs.{{FinReportSettings.incomeTaxPayableAccounts}} AS income_tax_payable
    FROM {{FinReportSettings}} rs
    WHERE rs.{{FinReportSettings.settingsKey}} = 'REPORTS'
),
range_sets AS (
    SELECT 'INTEREST_PAID' AS what, s.interest AS ranges FROM settings s
    UNION ALL SELECT 'INTEREST_PAID', s.interest_payable FROM settings s
    UNION ALL SELECT 'INCOME_TAXES_PAID', s.income_tax FROM settings s
    UNION ALL SELECT 'INCOME_TAXES_PAID', s.income_tax_payable FROM settings s
),
-- Paid = the expense less the increase in the payable: the net change of the expense and payable accounts together;
-- empty without settings.
paid AS (
    SELECT w.what,
           CASE WHEN EXISTS (SELECT 1 FROM range_sets rs WHERE rs.what = w.what AND rs.ranges IS NOT NULL)
                THEN COALESCE((SELECT SUM(c.range_net) FROM changes c WHERE EXISTS (
                         SELECT 1 FROM range_sets rs
                         CROSS JOIN UNNEST(STRING_TO_ARRAY(REPLACE(rs.ranges, ' ', ''), ',')) AS r (part)
                         WHERE rs.what = w.what
                           AND c.code COLLATE "C" BETWEEN SPLIT_PART(r.part, '-', 1)
                               AND COALESCE(NULLIF(SPLIT_PART(r.part, '-', 2), ''), SPLIT_PART(r.part, '-', 1)))),
                     0)
           END AS amount
    FROM (VALUES ('INTEREST_PAID'), ('INCOME_TAXES_PAID')) AS w (what)
),
out_rows AS (
    SELECT 1000 AS seq, 'OPERATING' AS lineCode, 'Cash flows from operating activities' AS label,
           'HEADING' AS kind, CAST(NULL AS numeric) AS amount
    UNION ALL SELECT 2000, 'NET_INCOME', 'Net income', 'LINE', f.net_income FROM figures f
    UNION ALL
    SELECT 3000 + CASE ab.tag WHEN 'DEP' THEN 1 ELSE 2 END, 'NONCASH_ITEM.' || ab.tag,
           CASE ab.tag WHEN 'DEP' THEN 'Depreciation' ELSE 'Unrealized foreign exchange (gain) loss' END,
           'LINE', ab.net
    FROM addbacks ab
    UNION ALL
    SELECT 4000 + ROW_NUMBER() OVER (ORDER BY sl.first_code), 'OPERATING.' || sl.first_code, sl.stmt_line, 'LINE',
           sl.amount
    FROM section_lines sl WHERE sl.section = 'OPERATING'
    UNION ALL SELECT 5000, 'NET_OPERATING', 'Net cash provided by (used in) operating activities', 'TOTAL',
                     f.net_income + f.addback + f.operating_wc FROM figures f
    UNION ALL SELECT 6000, 'INVESTING', 'Cash flows from investing activities', 'HEADING', NULL
    UNION ALL
    SELECT 7000 + ROW_NUMBER() OVER (ORDER BY sl.first_code), 'INVESTING.' || sl.first_code, sl.stmt_line, 'LINE',
           sl.amount
    FROM section_lines sl WHERE sl.section = 'INVESTING'
    UNION ALL SELECT 8000, 'NET_INVESTING', 'Net cash provided by (used in) investing activities', 'TOTAL',
                     f.investing FROM figures f
    UNION ALL SELECT 9000, 'FINANCING', 'Cash flows from financing activities', 'HEADING', NULL
    UNION ALL
    SELECT 10000 + ROW_NUMBER() OVER (ORDER BY sl.first_code), 'FINANCING.' || sl.first_code, sl.stmt_line, 'LINE',
           sl.amount
    FROM section_lines sl WHERE sl.section = 'FINANCING'
    UNION ALL SELECT 11000, 'NET_FINANCING', 'Net cash provided by (used in) financing activities', 'TOTAL',
                     f.financing FROM figures f
    UNION ALL SELECT 11500, 'FX_EFFECT', 'Effect of exchange rate changes on cash', 'LINE', f.cash_fx FROM figures f
    UNION ALL SELECT 12000, 'NET_CHANGE', 'Net increase (decrease) in cash and cash equivalents', 'TOTAL',
                     f.cash_change FROM figures f
    UNION ALL SELECT 13000, 'CASH_BEGINNING', 'Cash and cash equivalents, beginning', 'LINE',
                     f.cash_opening FROM figures f
    UNION ALL SELECT 14000, 'CASH_ENDING', 'Cash and cash equivalents, ending', 'TOTAL',
                     f.cash_opening + f.cash_change FROM figures f
    UNION ALL SELECT 15000, 'UNEXPLAINED', 'Unexplained difference', 'CHECK',
                     f.cash_change
                     - (f.net_income + f.addback + f.operating_wc + f.investing + f.financing + f.cash_fx)
              FROM figures f
    UNION ALL SELECT 16000, 'SUPPLEMENTAL', 'Supplemental disclosures', 'HEADING', NULL
    UNION ALL
    -- Non-cash investing and financing activities: what the non-cash postings put on those accounts.
    SELECT 17000 + ROW_NUMBER() OVER (ORDER BY MIN(a.code)), 'NONCASH.' || MIN(a.code),
           'Non-cash: ' || a.stmt_line, 'SUPPLEMENTAL', SUM(x.net)
    FROM excluded x JOIN accts a ON a.acct_key = x.acct_key
    WHERE x.tag = 'NONCASH' AND a.cf_class IN ('INVESTING', 'FINANCING')
    GROUP BY a.stmt_line
    UNION ALL
    SELECT CASE p.what WHEN 'INTEREST_PAID' THEN 18001 ELSE 18002 END, p.what,
           CASE p.what WHEN 'INTEREST_PAID' THEN 'Interest paid' ELSE 'Income taxes paid' END, 'SUPPLEMENTAL',
           p.amount
    FROM paid p
    UNION ALL
    -- Accounts with a change and no cash flow class: the statement does not explain them.
    SELECT 100000000 + ROW_NUMBER() OVER (ORDER BY u.code), 'UNCLASSIFIED.' || u.code, u.code || ' ' || u.acct_name,
           'UNCLASSIFIED', u.amount
    FROM unclassified u
)
SELECT seq, lineCode, label, kind, amount FROM out_rows
