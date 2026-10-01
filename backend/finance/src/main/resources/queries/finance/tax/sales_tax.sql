/*---
id: finance.tax.sales_tax
description: >-
  Sales tax by tax code for a period (FIN-TX-008, FIN-EXP-13): for every tax code the lines of the period's invoices
  and credit memos were taxed under, the taxable sales, the exempt or non-taxable sales and the tax collected, net of
  credit memos (a credit memo counts in its own period, FIN-TX-005) and of documents voided in the period, in US
  dollars, with the documents and the certificates that exempted them. From the subledger's tax rows, as computed
  when each document was posted; run with knownAt, as recorded at that time, so a past period reproduces.
entities: [FinInvoice, FinInvoiceTax]
params:
  from:    { like: FinInvoice.invoiceDate, required: true, description: the first day of the period }
  to:      { like: FinInvoice.invoiceDate, required: true, description: the last day of the period }
results:
  taxCode:      { from: FinInvoiceTax.taxCode }
  taxKind:      { from: FinInvoiceTax.taxKind }
  taxableSales: { from: FinInvoice.totalUsd }
  exemptSales:  { from: FinInvoice.totalUsd }
  taxCollected: { from: FinInvoice.totalUsd }
  documents:    { kind: { type: text, maxLength: 4000 } }
  certificates: { kind: { type: text, maxLength: 1000 } }
list:
  filters: [taxCode, taxKind]
  sorts:   [taxCode, taxableSales, exemptSales, taxCollected]
  defaultSort: { field: taxCode, asc: true }
  key: [taxCode, taxKind]
permissions: [fin.ar.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
WITH counted AS (
    -- Each posted document in the period, credit memos negative; a void in the period takes its document back.
    SELECT i.{{FinInvoice.invoiceId}} AS doc_key, i.{{FinInvoice.invoiceNo}} AS doc_no,
           i.{{FinInvoice.exchangeRate}} AS rate,
           CASE WHEN i.{{FinInvoice.kind}} = 'CREDIT_MEMO' THEN -1 ELSE 1 END AS sign
    FROM {{FinInvoice}} i
    WHERE i.{{FinInvoice.status}} IN ('POSTED', 'VOID', 'WRITTEN_OFF') AND i.{{FinInvoice.source}} <> 'OPENING'
      AND i.{{FinInvoice.invoiceDate}} >= :from AND i.{{FinInvoice.invoiceDate}} <= :to
    UNION ALL
    SELECT i.{{FinInvoice.invoiceId}}, i.{{FinInvoice.invoiceNo}}, i.{{FinInvoice.exchangeRate}},
           CASE WHEN i.{{FinInvoice.kind}} = 'CREDIT_MEMO' THEN 1 ELSE -1 END
    FROM {{FinInvoice}} i
    WHERE i.{{FinInvoice.status}} = 'VOID'
      AND i.{{FinInvoice.voidDate}} >= :from AND i.{{FinInvoice.voidDate}} <= :to
),
lines AS (
    SELECT t.{{FinInvoiceTax.taxCode}} AS code, t.{{FinInvoiceTax.taxKind}} AS tkind, c.doc_no,
           t.{{FinInvoiceTax.certificateNo}} AS cert,
           c.sign * ROUND(t.{{FinInvoiceTax.base}} * c.rate, 2) AS base_usd,
           c.sign * ROUND(t.{{FinInvoiceTax.tax}} * c.rate, 2) AS tax_usd
    FROM {{FinInvoiceTax}} t
    JOIN counted c ON c.doc_key = t.{{FinInvoiceTax.invoiceId}}
    WHERE t.{{FinInvoiceTax.lineNo}} IS NOT NULL
)
SELECT code AS taxCode,
       tkind AS taxKind,
       SUM(CASE WHEN tkind = 'TAXABLE' THEN base_usd ELSE 0 END) AS taxableSales,
       SUM(CASE WHEN tkind = 'TAXABLE' THEN 0 ELSE base_usd END) AS exemptSales,
       SUM(tax_usd) AS taxCollected,
       STRING_AGG(DISTINCT doc_no, ', ' ORDER BY doc_no) AS documents,
       STRING_AGG(DISTINCT cert, ', ' ORDER BY cert) AS certificates
FROM lines
GROUP BY code, tkind
