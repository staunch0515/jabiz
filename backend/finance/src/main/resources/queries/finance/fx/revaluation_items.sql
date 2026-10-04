/*---
id: finance.fx.revaluation_items
description: >-
  The open foreign currency monetary items at the end of a day and their remeasurement (FIN-FX-005, FX-006; F7 plan
  decisions D6, D9): receivables and payables documents in a foreign currency with what is open of them in it and the
  US dollars they carry at their own rates (as the agings have them, applications dated later aside), and bank accounts
  in a foreign currency with the closing balance of their last statement by the day and the dollars their account
  carries (with the statement's last day: a run needs one ending on the day). Each is remeasured at the rate of the type asked for on the day or the days just before, else at the spot
  rate of those days; amounts are signed as the books carry them (receivables and banks positive, payables negative),
  so the difference is a gain when positive. A currency without a rate shows no rate. Run with knownAt, the items and
  rates as recorded then: how a revaluation run was computed.
entities: [FinInvoice, FinApplication, FinBill, FinApApplication, FinBankAccount, FinBankStatement, LedgerAccount,
  LedgerEntry, FinPosting, FinExchangeRate]
params:
  revaluationDate: { like: FinApplication.applicationDate, required: true, description: "the day remeasured, at its end" }
  rateType:        { like: FinExchangeRate.rateType, required: true, description: "the rate type remeasured at, as the foreign currency settings have it" }
  toleranceDays:   { kind: { type: numeric, precision: 2, scale: 0 }, required: true, description: "the days before the day a rate may be taken from" }
results:
  kind:        { kind: { type: text, maxLength: 10 } }
  documentId:  { kind: { type: text, maxLength: 36 } }
  documentNo:  { kind: { type: text, maxLength: 40 } }
  partyCode:   { kind: { type: text, maxLength: 20 } }
  account:     { kind: { type: text, maxLength: 20 } }
  statementDate: { from: FinBankStatement.toDate }
  currency:    { from: FinInvoice.currency }
  openAmount:  { from: FinInvoice.openAmount }
  carryingUsd: { from: FinInvoice.openAmountUsd }
  rateDate:    { from: FinExchangeRate.rateDate }
  rateType:    { from: FinExchangeRate.rateType }
  rate:        { from: FinExchangeRate.rate }
  revaluedUsd: { from: FinInvoice.openAmountUsd }
  difference:  { from: FinInvoice.openAmountUsd }
list:
  filters: [kind, currency, partyCode]
  sorts:   [kind, documentNo, currency, difference]
  defaultSort: { field: kind, asc: true }
  key: [kind, documentId]
permissions: [fin.fx.run]
report:
  period: { to: revaluationDate }
  landscape: true
---*/
WITH receivables AS (
    SELECT CAST(i.{{FinInvoice.invoiceId}} AS varchar) AS doc_key, i.{{FinInvoice.invoiceNo}} AS doc_no,
           i.{{FinInvoice.customerCode}} AS party, i.{{FinInvoice.currency}} AS cur,
           CASE WHEN i.{{FinInvoice.kind}} = 'CREDIT_MEMO' THEN -1 ELSE 1 END AS sign,
           i.{{FinInvoice.total}} AS doc_total, i.{{FinInvoice.totalUsd}} AS doc_total_usd
    FROM {{FinInvoice}} i
    WHERE i.{{FinInvoice.status}} IN ('POSTED', 'VOID', 'WRITTEN_OFF')
      AND i.{{FinInvoice.currency}} <> 'USD'
      AND COALESCE(i.{{FinInvoice.postingDate}}, i.{{FinInvoice.invoiceDate}}) <= :revaluationDate
      AND (i.{{FinInvoice.voidDate}} IS NULL OR i.{{FinInvoice.voidDate}} > :revaluationDate)
),
ar_cleared AS (
    SELECT CAST(a.{{FinApplication.invoiceId}} AS varchar) AS doc_key,
           SUM(a.{{FinApplication.amount}} + COALESCE(a.{{FinApplication.discount}}, 0)) AS done,
           SUM(a.{{FinApplication.amountUsd}} + COALESCE(a.{{FinApplication.discount}}, 0)) AS done_usd
    FROM {{FinApplication}} a
    WHERE a.{{FinApplication.applicationDate}} <= :revaluationDate
    GROUP BY a.{{FinApplication.invoiceId}}
    UNION ALL
    SELECT a.{{FinApplication.sourceId}}, SUM(a.{{FinApplication.amount}}),
           SUM(COALESCE(a.{{FinApplication.sourceAmountUsd}}, a.{{FinApplication.amountUsd}}))
    FROM {{FinApplication}} a
    WHERE a.{{FinApplication.sourceKind}} = 'CREDIT_MEMO' AND a.{{FinApplication.applicationDate}} <= :revaluationDate
    GROUP BY a.{{FinApplication.sourceId}}
),
payables AS (
    SELECT CAST(b.{{FinBill.billId}} AS varchar) AS doc_key, b.{{FinBill.billNo}} AS doc_no,
           b.{{FinBill.vendorCode}} AS party, b.{{FinBill.currency}} AS cur,
           -- What is owed is a credit: negative, a vendor credit positive.
           CASE WHEN b.{{FinBill.kind}} = 'CREDIT' THEN 1 ELSE -1 END AS sign,
           b.{{FinBill.total}} AS doc_total, COALESCE(b.{{FinBill.totalUsd}}, b.{{FinBill.total}}) AS doc_total_usd
    FROM {{FinBill}} b
    WHERE b.{{FinBill.status}} IN ('POSTED', 'VOID')
      AND b.{{FinBill.currency}} <> 'USD'
      AND COALESCE(b.{{FinBill.postingDate}}, b.{{FinBill.invoiceDate}}) <= :revaluationDate
      AND (b.{{FinBill.voidDate}} IS NULL OR b.{{FinBill.voidDate}} > :revaluationDate)
),
ap_cleared AS (
    SELECT CAST(a.{{FinApApplication.billId}} AS varchar) AS doc_key,
           SUM(a.{{FinApApplication.amount}} + COALESCE(a.{{FinApApplication.discount}}, 0)) AS done,
           SUM(COALESCE(a.{{FinApApplication.amountUsd}}, a.{{FinApApplication.amount}})
               + COALESCE(a.{{FinApApplication.discount}}, 0)) AS done_usd
    FROM {{FinApApplication}} a
    WHERE a.{{FinApApplication.applicationDate}} <= :revaluationDate
    GROUP BY a.{{FinApApplication.billId}}
    UNION ALL
    SELECT a.{{FinApApplication.sourceId}}, SUM(a.{{FinApApplication.amount}}),
           SUM(COALESCE(a.{{FinApApplication.sourceAmountUsd}}, a.{{FinApApplication.amountUsd}},
               a.{{FinApApplication.amount}}))
    FROM {{FinApApplication}} a
    WHERE a.{{FinApApplication.sourceKind}} = 'CREDIT' AND a.{{FinApApplication.applicationDate}} <= :revaluationDate
    GROUP BY a.{{FinApApplication.sourceId}}
),
-- Summed once a document and joined (F11c), as in the agings.
ar_cleared_by AS (
    SELECT doc_key, SUM(done) AS done, SUM(done_usd) AS done_usd FROM ar_cleared GROUP BY doc_key
),
ap_cleared_by AS (
    SELECT doc_key, SUM(done) AS done, SUM(done_usd) AS done_usd FROM ap_cleared GROUP BY doc_key
),
banks AS (
    SELECT b.{{FinBankAccount.bankCode}} AS bank_key, b.{{FinBankAccount.currency}} AS cur,
           b.{{FinBankAccount.glAccount}} AS gl
    FROM {{FinBankAccount}} b
    WHERE b.{{FinBankAccount.currency}} <> 'USD'
),
bank_books AS (
    SELECT k.bank_key,
           COALESCE(SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                             ELSE -e.{{LedgerEntry.amount}} END), 0) AS book_usd
    FROM banks k
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountCode}} = k.gl
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.accountId}} = a.{{LedgerAccount.accountId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE fp.{{FinPosting.postingDate}} <= :revaluationDate
      -- What the account carries before any remeasurement: a run's own entries aside.
      AND fp.{{FinPosting.sourceEntity}} <> 'FinFxRevaluationRun'
    GROUP BY k.bank_key
),
bank_statements AS (
    SELECT DISTINCT ON (s.{{FinBankStatement.bankCode}})
           s.{{FinBankStatement.bankCode}} AS bank_key, s.{{FinBankStatement.closingBalance}} AS closing,
           s.{{FinBankStatement.toDate}} AS closed_on
    FROM {{FinBankStatement}} s
    WHERE s.{{FinBankStatement.toDate}} <= :revaluationDate
    ORDER BY s.{{FinBankStatement.bankCode}}, s.{{FinBankStatement.toDate}} DESC
),
items AS (
    SELECT 'RECEIVABLE' AS kind, d.doc_key, d.doc_no, d.party, CAST(NULL AS varchar) AS gl,
           CAST(NULL AS date) AS stmt_on, d.cur,
           d.sign * (d.doc_total - COALESCE(c.done, 0)) AS open_amt,
           d.sign * (d.doc_total_usd - COALESCE(c.done_usd, 0)) AS carry_usd
    FROM receivables d
    LEFT JOIN ar_cleared_by c ON c.doc_key = d.doc_key
    UNION ALL
    SELECT 'PAYABLE', d.doc_key, d.doc_no, d.party, CAST(NULL AS varchar), CAST(NULL AS date), d.cur,
           d.sign * (d.doc_total - COALESCE(c.done, 0)), d.sign * (d.doc_total_usd - COALESCE(c.done_usd, 0))
    FROM payables d
    LEFT JOIN ap_cleared_by c ON c.doc_key = d.doc_key
    UNION ALL
    -- A bank account in a foreign currency: what its last statement says it holds, and what its account carries.
    SELECT 'BANK', k.bank_key, k.bank_key, CAST(NULL AS varchar), k.gl, st.closed_on, k.cur, COALESCE(st.closing, 0),
           COALESCE(bb.book_usd, 0)
    FROM banks k
    LEFT JOIN bank_statements st ON st.bank_key = k.bank_key
    LEFT JOIN bank_books bb ON bb.bank_key = k.bank_key
),
rated AS (
    SELECT it.*, r.rate_on, r.rate_kind, r.rate_value
    FROM items it
    LEFT JOIN LATERAL (
        SELECT x.{{FinExchangeRate.rateDate}} AS rate_on, x.{{FinExchangeRate.rateType}} AS rate_kind,
               x.{{FinExchangeRate.rate}} AS rate_value
        FROM {{FinExchangeRate}} x
        WHERE x.{{FinExchangeRate.fromCurrency}} = it.cur AND x.{{FinExchangeRate.toCurrency}} = 'USD'
          AND (x.{{FinExchangeRate.rateType}} = :rateType OR x.{{FinExchangeRate.rateType}} = 'SPOT')
          AND x.{{FinExchangeRate.rateDate}} <= :revaluationDate
          AND x.{{FinExchangeRate.rateDate}} >= CAST(:revaluationDate AS date) - CAST(:toleranceDays AS integer)
        -- The type asked for first; the spot rate of the days only when it has none.
        ORDER BY CASE WHEN x.{{FinExchangeRate.rateType}} = :rateType THEN 0 ELSE 1 END,
                 x.{{FinExchangeRate.rateDate}} DESC
        LIMIT 1
    ) r ON true
    WHERE it.open_amt <> 0 OR it.carry_usd <> 0
)
SELECT kind,
       doc_key    AS documentId,
       doc_no     AS documentNo,
       party      AS partyCode,
       gl         AS account,
       stmt_on    AS statementDate,
       cur        AS currency,
       open_amt   AS openAmount,
       carry_usd  AS carryingUsd,
       rate_on    AS rateDate,
       rate_kind  AS rateType,
       rate_value AS rate,
       ROUND(open_amt * rate_value, 2) AS revaluedUsd,
       ROUND(open_amt * rate_value, 2) - carry_usd AS difference
FROM rated
