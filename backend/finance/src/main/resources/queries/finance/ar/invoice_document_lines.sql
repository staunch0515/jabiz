/*---
id: finance.ar.invoice_document_lines
description: >-
  The lines of one invoice or credit memo as its document shows them (FIN-AR-005): quantity and unit price as entered,
  without trailing zeros, the amount and the tax code that applied.
entities: [FinInvoiceLine, FinInvoice]
params:
  invoiceId: { like: FinInvoiceLine.invoiceId, required: true, description: The invoice or credit memo }
results:
  lineNo:      { from: FinInvoiceLine.lineNo }
  description: { from: FinInvoiceLine.description }
  quantity:    { kind: { type: text, maxLength: 30 } }
  unitPrice:   { kind: { type: text, maxLength: 30 } }
  amount:      { from: FinInvoiceLine.amount }
  taxCode:     { from: FinInvoiceLine.taxCode }
list:
  sorts: [lineNo]
  defaultSort: { field: lineNo, asc: true }
  key: [lineNo]
permissions: [fin.ar.read]
---*/
SELECT
    l.{{FinInvoiceLine.lineNo}}      AS lineNo,
    l.{{FinInvoiceLine.description}} AS description,
    -- 100 rather than 100.0000; a price keeps its cents (400.00) and up to four places (0.3333).
    rtrim(to_char(l.{{FinInvoiceLine.quantity}}, 'FM999,999,999,990.9999'), '.') AS quantity,
    to_char(l.{{FinInvoiceLine.unitPrice}}, 'FM9,999,999,999,990.0099') AS unitPrice,
    l.{{FinInvoiceLine.amount}}      AS amount,
    COALESCE(l.{{FinInvoiceLine.taxCode}}, i.{{FinInvoice.taxCode}}) AS taxCode
FROM {{FinInvoiceLine}} l
JOIN {{FinInvoice}} i ON i.{{FinInvoice.invoiceId}} = l.{{FinInvoiceLine.invoiceId}}
WHERE l.{{FinInvoiceLine.invoiceId}} = :invoiceId
