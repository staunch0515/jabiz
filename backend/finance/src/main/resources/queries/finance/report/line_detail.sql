/*---
id: finance.report.line_detail
description: >-
  What makes a statement figure (FIN-RP-006; ROADMAP F9 decision D7): the entry lines of the accounts given (code
  ranges, as a layout row writes them) over the span of the figure, each with its posting, document and the source to
  open, as the statements count them: closing entries never, period 13 of the last day's fiscal year unless left
  out, one department or location if asked, the books as recorded up to a time if given. The span is the month, the fiscal quarter or year holding the last day, or a balance: then each
  account's balance before the month (or the fiscal year, YEAR_BALANCE) comes first, read from the period balances,
  so that the lines add up to the figure. A first day given replaces the span's.
entities: [LedgerAccount, LedgerTransaction, LedgerEntry, FinAccount, FinPosting, FinPeriod, FinPeriodBalance]
params:
  accounts:    { kind: { type: text, maxLength: 500 }, required: true, description: "the accounts: code ranges, comma separated" }
  through:     { like: FinPosting.postingDate, required: true, description: the figure's last day }
  span:        { kind: { type: text, maxLength: 12 }, description: "MONTH, QUARTER, YEAR, BALANCE (the default) or YEAR_BALANCE" }
  from:        { like: FinPosting.postingDate, description: "the first day, in place of the span's" }
  adjustments: { kind: { type: bool }, description: "whether period 13 of the last day's fiscal year counts; yes when not given" }
  department:  { like: LedgerEntry.dimension1, description: "only this department's entries" }
  location:    { like: LedgerEntry.dimension2, description: "only this location's entries" }
  knownAt:     { like: LedgerTransaction.bookingTime, description: "if given, the books as recorded at this time" }
results:
  lineKey:      { kind: { type: text, maxLength: 80 } }
  kind:         { kind: { type: text, maxLength: 10 } }
  accountCode:  { from: LedgerAccount.accountCode }
  accountName:  { from: LedgerAccount.accountName }
  postingDate:  { from: FinPosting.postingDate }
  glNo:         { from: FinPosting.glNo }
  lineNo:       { from: LedgerEntry.lineNo }
  source:       { from: FinPosting.source }
  documentNo:   { from: FinPosting.documentNo }
  description:  { from: LedgerTransaction.description }
  memo:         { from: LedgerEntry.memo }
  amount:       { from: LedgerEntry.amount }
  sourceEntity: { from: FinPosting.sourceEntity }
  sourceId:     { from: FinPosting.sourceId }
list:
  filters: [accountCode, kind, source, documentNo]
  sorts:   [accountCode, postingDate, glNo]
  defaultSort: { field: accountCode, asc: true }
  key: [lineKey]
permissions: [ledger.read]
timeSlice: { knownAt: knownAt }
report:
  period: { from: from, to: through }
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
bounds AS (
    SELECT COALESCE(CAST(:from AS date),
               CASE CASE COALESCE(CAST(:span AS text), 'BALANCE') WHEN 'YEAR_BALANCE' THEN 'YEAR'
                         ELSE COALESCE(CAST(:span AS text), 'BALANCE') END
                   WHEN 'QUARTER' THEN (SELECT q.{{FinPeriod.startDate}} FROM {{FinPeriod}} q, here h
                                        WHERE q.{{FinPeriod.fiscalYear}} = h.fy
                                          AND NOT COALESCE(q.{{FinPeriod.adjustment}}, false)
                                          AND q.{{FinPeriod.periodNo}} = FLOOR((h.pno - 1) / 3) * 3 + 1)
                   WHEN 'YEAR' THEN (SELECT MIN(y.{{FinPeriod.startDate}}) FROM {{FinPeriod}} y, here h
                                     WHERE y.{{FinPeriod.fiscalYear}} = h.fy
                                       AND NOT COALESCE(y.{{FinPeriod.opening}}, false))
                   ELSE (SELECT h.month_start FROM here h) END,
               CAST(:through AS date)) AS first_day,
           (SELECT MAX(yp.{{FinPeriod.fiscalYear}}) FROM {{FinPeriod}} yp
            WHERE yp.{{FinPeriod.startDate}} <= :through AND yp.{{FinPeriod.endDate}} >= :through) AS last_year,
           COALESCE(CAST(:span AS text), 'BALANCE') IN ('BALANCE', 'YEAR_BALANCE') AS with_opening
),
accts AS (
    SELECT a.{{LedgerAccount.accountId}} AS acct_key, a.{{LedgerAccount.accountCode}} AS code,
           a.{{LedgerAccount.accountName}} AS acct_name
    FROM {{LedgerAccount}} a
    WHERE NOT COALESCE(a.{{LedgerAccount.summary}}, false)
      AND EXISTS (SELECT 1 FROM UNNEST(STRING_TO_ARRAY(REPLACE(CAST(:accounts AS text), ' ', ''), ',')) AS r (part)
                  WHERE a.{{LedgerAccount.accountCode}} COLLATE "C" BETWEEN SPLIT_PART(r.part, '-', 1)
                        AND COALESCE(NULLIF(SPLIT_PART(r.part, '-', 2), ''), SPLIT_PART(r.part, '-', 1)))
),
-- The periods before the first day, for the balance: whole ones from their latest snapshot (period 13 entry by entry,
-- without its closing entries), the one the first day falls in entry by entry.
periods AS (
    SELECT p.{{FinPeriod.periodKey}} AS pkey,
           p.{{FinPeriod.endDate}} < b.first_day AND NOT COALESCE(p.{{FinPeriod.adjustment}}, false) AS whole,
           COALESCE(p.{{FinPeriod.adjustment}}, false) AND p.{{FinPeriod.fiscalYear}} = b.last_year
               AND NOT COALESCE(CAST(:adjustments AS boolean), true) AS left_out
    FROM {{FinPeriod}} p CROSS JOIN bounds b
    WHERE p.{{FinPeriod.startDate}} <= :through
),
snapshots AS (
    SELECT s.{{FinPeriodBalance.periodKey}} AS pkey, MAX(s.{{FinPeriodBalance.countedTo}}) AS counted
    FROM {{FinPeriodBalance}} s
    JOIN periods pr ON pr.pkey = s.{{FinPeriodBalance.periodKey}}
    CROSS JOIN bounds b
    WHERE pr.whole AND b.with_opening
      AND (CAST(:knownAt AS timestamptz) IS NULL OR s.{{FinPeriodBalance.countedTo}} <= :knownAt)
    GROUP BY s.{{FinPeriodBalance.periodKey}}
),
lines AS (
    SELECT e.{{LedgerEntry.accountId}} AS acct_key, fp.{{FinPosting.postingDate}} AS posting_day,
           fp.{{FinPosting.postingDate}} < b.first_day AS before,
           fp.{{FinPosting.glNo}} AS gl_ref, e.{{LedgerEntry.lineNo}} AS entry_no, fp.{{FinPosting.source}} AS src,
           fp.{{FinPosting.documentNo}} AS doc_ref, t.{{LedgerTransaction.description}} AS what,
           e.{{LedgerEntry.memo}} AS line_memo,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END AS net,
           fp.{{FinPosting.sourceEntity}} AS doc_entity, fp.{{FinPosting.sourceId}} AS doc_key
    FROM {{LedgerEntry}} e
    JOIN accts ac ON ac.acct_key = e.{{LedgerEntry.accountId}}
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN periods pr ON pr.pkey = fp.{{FinPosting.periodKey}}
    CROSS JOIN bounds b
    LEFT JOIN snapshots sn ON sn.pkey = fp.{{FinPosting.periodKey}}
    WHERE fp.{{FinPosting.postingDate}} <= :through
      AND (fp.{{FinPosting.postingDate}} >= b.first_day
           OR b.with_opening AND (sn.counted IS NULL OR t.{{LedgerTransaction.createdTime}} > sn.counted))
      AND NOT pr.left_out
      -- CLS: the general ledger source of the year-end closing entries (JournalEntities.postingSource).
      AND fp.{{FinPosting.source}} <> 'CLS'
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
      AND (CAST(:department AS text) IS NULL OR e.{{LedgerEntry.dimension1}} = :department)
      AND (CAST(:location AS text) IS NULL OR e.{{LedgerEntry.dimension2}} = :location)
),
-- Each account's balance before the first day: its snapshots and the lines not in them.
openings AS (
    SELECT ac.acct_key, ac.code, ac.acct_name,
           COALESCE((SELECT SUM(s.{{FinPeriodBalance.debit}} - s.{{FinPeriodBalance.credit}})
                     FROM {{FinPeriodBalance}} s
                     JOIN snapshots sn ON sn.pkey = s.{{FinPeriodBalance.periodKey}}
                                      AND sn.counted = s.{{FinPeriodBalance.countedTo}}
                     WHERE s.{{FinPeriodBalance.accountCode}} = ac.code
                       AND (CAST(:department AS text) IS NULL OR s.{{FinPeriodBalance.department}} = :department)
                       AND (CAST(:location AS text) IS NULL OR s.{{FinPeriodBalance.location}} = :location)), 0)
           + COALESCE((SELECT SUM(l.net) FROM lines l
                       WHERE l.acct_key = ac.acct_key AND l.before), 0) AS balance
    FROM accts ac
    CROSS JOIN bounds b
    WHERE b.with_opening
)
SELECT o.code || ':OPENING' AS lineKey, 'OPENING' AS kind, o.code AS accountCode, o.acct_name AS accountName,
       CAST(NULL AS date) AS postingDate, CAST(NULL AS text) AS glNo, CAST(NULL AS numeric) AS lineNo,
       CAST(NULL AS text) AS source, CAST(NULL AS text) AS documentNo, CAST(NULL AS text) AS description,
       CAST(NULL AS text) AS memo, o.balance AS amount, CAST(NULL AS text) AS sourceEntity,
       CAST(NULL AS text) AS sourceId
FROM openings o
WHERE o.balance <> 0
UNION ALL
SELECT ac.code || ':' || l.gl_ref || ':' || l.entry_no, 'ENTRY', ac.code, ac.acct_name, l.posting_day, l.gl_ref,
       l.entry_no, l.src, l.doc_ref, l.what, l.line_memo, l.net, l.doc_entity, l.doc_key
FROM lines l JOIN accts ac ON ac.acct_key = l.acct_key
WHERE NOT l.before
