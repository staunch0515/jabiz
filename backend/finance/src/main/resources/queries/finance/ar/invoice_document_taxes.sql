/*---
id: finance.ar.invoice_document_taxes
description: >-
  The sales tax of one invoice or credit memo as its document shows it (FIN-AR-005, FIN-TX-003, FIN-TX-004): per
  jurisdiction the taxable base, the rate and the tax, as computed when it was posted; then what was not taxed and
  why, with the exemption certificate it rests on.
entities: [FinInvoiceTax, FinTaxJurisdiction]
params:
  invoiceId: { like: FinInvoiceTax.invoiceId, required: true, description: The invoice or credit memo }
results:
  jurisdiction: { kind: { type: text, maxLength: 300 } }
  base:         { from: FinInvoiceTax.base }
  rate:         { kind: { type: text, maxLength: 20 } }
  tax:          { from: FinInvoiceTax.tax }
  code:         { kind: { type: text, maxLength: 80 } }
list:
  sorts: [code]
  defaultSort: { field: code, asc: true }
  key: [code]
permissions: [fin.ar.read]
---*/
SELECT
    COALESCE(j.{{FinTaxJurisdiction.jurisdictionName}}, x.{{FinInvoiceTax.jurisdiction}}) AS jurisdiction,
    x.{{FinInvoiceTax.base}} AS base,
    trim_scale(x.{{FinInvoiceTax.ratePercent}}) || '%' AS rate,
    x.{{FinInvoiceTax.tax}}  AS tax,
    'J:' || x.{{FinInvoiceTax.jurisdiction}} AS code
FROM {{FinInvoiceTax}} x
LEFT JOIN {{FinTaxJurisdiction}} j
    ON j.{{FinTaxJurisdiction.jurisdictionCode}} = x.{{FinInvoiceTax.jurisdiction}}
WHERE x.{{FinInvoiceTax.invoiceId}} = :invoiceId
  AND x.{{FinInvoiceTax.lineNo}} IS NULL
  AND x.{{FinInvoiceTax.jurisdiction}} IS NOT NULL
UNION ALL
-- The lines not taxed, by why: "Exempt: resale, certificate RC-3301".
SELECT
    CASE WHEN x.{{FinInvoiceTax.taxKind}} = 'EXEMPT' THEN 'Exempt' ELSE 'Not taxable' END
        || COALESCE(': ' || lower(replace(x.{{FinInvoiceTax.reason}}, '_', ' ')), '')
        || COALESCE(', certificate ' || x.{{FinInvoiceTax.certificateNo}}, '') AS jurisdiction,
    SUM(x.{{FinInvoiceTax.base}}) AS base,
    NULL AS rate,
    SUM(x.{{FinInvoiceTax.tax}}) AS tax,
    'X:' || x.{{FinInvoiceTax.taxKind}} || ':' || COALESCE(x.{{FinInvoiceTax.reason}}, '') || ':'
        || COALESCE(x.{{FinInvoiceTax.certificateNo}}, '') AS code
FROM {{FinInvoiceTax}} x
WHERE x.{{FinInvoiceTax.invoiceId}} = :invoiceId
  AND x.{{FinInvoiceTax.lineNo}} IS NOT NULL
  AND x.{{FinInvoiceTax.taxKind}} <> 'TAXABLE'
GROUP BY x.{{FinInvoiceTax.taxKind}}, x.{{FinInvoiceTax.reason}}, x.{{FinInvoiceTax.certificateNo}}
