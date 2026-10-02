/*---
id: finance.tax.sales_tax_return
description: >-
  The data of the sales tax returns for a filing period (FIN-TX-008): per state and jurisdiction, the taxable sales,
  the credits against them, the tax collected, the tax credited and the tax due; and per state the exempt and
  non-taxable sales by reason (resale, services, exports, no sales tax). Gross sales of a state are the sum of its
  rows. Credit memos count in their own period (FIN-TX-005), voids in theirs. Run with knownAt, as recorded at that time,
  so a past period reproduces.
  An invoice booked after its date (a prior-period item, FIN-PC-007) counts in the period it is booked in, with its tax.
entities: [FinInvoice, FinInvoiceTax, FinTaxCode, FinTaxJurisdiction]
params:
  from:    { like: FinInvoice.invoiceDate, required: true, description: the first day of the filing period }
  to:      { like: FinInvoice.invoiceDate, required: true, description: the last day of the filing period }
  state:   { like: FinTaxCode.state, description: one state only }
results:
  state:        { from: FinTaxCode.state }
  jurisdiction: { from: FinInvoiceTax.jurisdiction }
  category:     { kind: { type: text, maxLength: 30 } }
  sales:        { from: FinInvoice.totalUsd }
  credits:      { from: FinInvoice.totalUsd }
  netSales:     { from: FinInvoice.totalUsd }
  taxCollected: { from: FinInvoice.totalUsd }
  taxCredited:  { from: FinInvoice.totalUsd }
  taxDue:       { from: FinInvoice.totalUsd }
list:
  filters: [state, jurisdiction, category]
  sorts:   [state, jurisdiction, category]
  defaultSort: { field: state, asc: true }
  key: [state, jurisdiction, category]
permissions: [fin.ar.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
WITH counted AS (
    SELECT i.{{FinInvoice.invoiceId}} AS doc_key, i.{{FinInvoice.exchangeRate}} AS rate,
           i.{{FinInvoice.taxCode}} AS ship_to,
           CASE WHEN i.{{FinInvoice.kind}} = 'CREDIT_MEMO' THEN -1 ELSE 1 END AS sign
    FROM {{FinInvoice}} i
    WHERE i.{{FinInvoice.status}} IN ('POSTED', 'VOID', 'WRITTEN_OFF') AND i.{{FinInvoice.source}} <> 'OPENING'
      AND COALESCE(i.{{FinInvoice.postingDate}}, i.{{FinInvoice.invoiceDate}}) >= :from
      AND COALESCE(i.{{FinInvoice.postingDate}}, i.{{FinInvoice.invoiceDate}}) <= :to
    UNION ALL
    SELECT i.{{FinInvoice.invoiceId}}, i.{{FinInvoice.exchangeRate}}, i.{{FinInvoice.taxCode}},
           CASE WHEN i.{{FinInvoice.kind}} = 'CREDIT_MEMO' THEN 1 ELSE -1 END
    FROM {{FinInvoice}} i
    WHERE i.{{FinInvoice.status}} = 'VOID'
      AND i.{{FinInvoice.voidDate}} >= :from AND i.{{FinInvoice.voidDate}} <= :to
),
taxed AS (
    -- Each jurisdiction's tax as computed per document.
    SELECT j.{{FinTaxJurisdiction.state}} AS st, t.{{FinInvoiceTax.jurisdiction}} AS jur, 'TAXABLE' AS cat,
           c.sign, ROUND(t.{{FinInvoiceTax.base}} * c.rate, 2) AS base_usd,
           ROUND(t.{{FinInvoiceTax.tax}} * c.rate, 2) AS tax_usd
    FROM {{FinInvoiceTax}} t
    JOIN counted c ON c.doc_key = t.{{FinInvoiceTax.invoiceId}}
    LEFT JOIN {{FinTaxJurisdiction}} j ON j.{{FinTaxJurisdiction.jurisdictionCode}} = t.{{FinInvoiceTax.jurisdiction}}
    WHERE t.{{FinInvoiceTax.lineNo}} IS NULL
),
exempt AS (
    -- Lines that bore no tax, by the reason their code gives, in the state of their code or, for a code of no
    -- state (a non-taxable service), of the document's ship-to code.
    SELECT COALESCE(tc.{{FinTaxCode.state}}, hc.{{FinTaxCode.state}}) AS st, CAST(NULL AS varchar) AS jur,
           COALESCE(t.{{FinInvoiceTax.reason}}, t.{{FinInvoiceTax.taxKind}}) AS cat, c.sign,
           ROUND(t.{{FinInvoiceTax.base}} * c.rate, 2) AS base_usd, CAST(0 AS numeric) AS tax_usd
    FROM {{FinInvoiceTax}} t
    JOIN counted c ON c.doc_key = t.{{FinInvoiceTax.invoiceId}}
    LEFT JOIN {{FinTaxCode}} tc ON tc.{{FinTaxCode.taxCode}} = t.{{FinInvoiceTax.taxCode}}
    LEFT JOIN {{FinTaxCode}} hc ON hc.{{FinTaxCode.taxCode}} = c.ship_to
    WHERE t.{{FinInvoiceTax.lineNo}} IS NOT NULL AND t.{{FinInvoiceTax.taxKind}} <> 'TAXABLE'
),
rows_all AS (
    SELECT * FROM taxed UNION ALL SELECT * FROM exempt
)
SELECT st AS state,
       jur AS jurisdiction,
       cat AS category,
       SUM(CASE WHEN sign > 0 THEN base_usd ELSE 0 END) AS sales,
       SUM(CASE WHEN sign < 0 THEN base_usd ELSE 0 END) AS credits,
       SUM(sign * base_usd) AS netSales,
       SUM(CASE WHEN sign > 0 THEN tax_usd ELSE 0 END) AS taxCollected,
       SUM(CASE WHEN sign < 0 THEN tax_usd ELSE 0 END) AS taxCredited,
       SUM(sign * tax_usd) AS taxDue
FROM rows_all
WHERE CAST(:state AS varchar) IS NULL OR st = :state
GROUP BY st, jur, cat
