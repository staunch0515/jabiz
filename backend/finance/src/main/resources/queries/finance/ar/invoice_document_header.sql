/*---
id: finance.ar.invoice_document_header
description: >-
  One invoice or credit memo as its document shows it (FIN-AR-005): number, dates, terms, the customer with the
  billing and shipping addresses in effect when read (the invoice's date, FIN-AR-001), the invoice a credit memo
  credits, and the totals in the document's currency. The contact's e-mail is where it is sent.
entities: [FinInvoice, FinCustomer, FinPaymentTerms]
params:
  invoiceId: { like: FinInvoice.invoiceId, required: true, description: The invoice or credit memo }
results:
  invoiceNo:         { from: FinInvoice.invoiceNo }
  kind:              { from: FinInvoice.kind }
  invoiceDate:       { from: FinInvoice.invoiceDate }
  dueDate:           { from: FinInvoice.dueDate }
  terms:             { from: FinPaymentTerms.description }
  currency:          { from: FinInvoice.currency }
  reference:         { from: FinInvoice.reference }
  originalInvoiceNo: { from: FinInvoice.invoiceNo }
  customerCode:      { from: FinInvoice.customerCode }
  customerName:      { from: FinCustomer.legalName }
  contactName:       { from: FinCustomer.contactName }
  billingStreet:     { from: FinCustomer.billingStreet }
  billingCityLine:   { kind: { type: text, maxLength: 200 } }
  billingCountry:    { from: FinCustomer.billingCountry }
  shippingStreet:    { from: FinCustomer.shippingStreet }
  shippingCityLine:  { kind: { type: text, maxLength: 200 } }
  shippingCountry:   { from: FinCustomer.shippingCountry }
  contactEmail:      { from: FinCustomer.contactEmail }
  description:       { from: FinInvoice.description }
  subtotal:          { from: FinInvoice.subtotal }
  taxTotal:          { from: FinInvoice.taxTotal }
  total:             { from: FinInvoice.total }
list:
  sorts: [invoiceNo]
  defaultSort: { field: invoiceNo, asc: true }
  key: [invoiceNo]
permissions: [fin.ar.read]
---*/
SELECT
    i.{{FinInvoice.invoiceNo}}    AS invoiceNo,
    i.{{FinInvoice.kind}}         AS kind,
    i.{{FinInvoice.invoiceDate}}  AS invoiceDate,
    i.{{FinInvoice.dueDate}}      AS dueDate,
    COALESCE(t.{{FinPaymentTerms.description}}, i.{{FinInvoice.termsCode}}) AS terms,
    i.{{FinInvoice.currency}}     AS currency,
    i.{{FinInvoice.reference}}    AS reference,
    o.{{FinInvoice.invoiceNo}}    AS originalInvoiceNo,
    i.{{FinInvoice.customerCode}} AS customerCode,
    c.{{FinCustomer.legalName}}   AS customerName,
    c.{{FinCustomer.contactName}} AS contactName,
    c.{{FinCustomer.billingStreet}} AS billingStreet,
    NULLIF(concat_ws(', ', NULLIF(c.{{FinCustomer.billingCity}}, ''),
        NULLIF(concat_ws(' ', NULLIF(c.{{FinCustomer.billingState}}, ''),
            NULLIF(c.{{FinCustomer.billingPostalCode}}, '')), '')), '') AS billingCityLine,
    c.{{FinCustomer.billingCountry}} AS billingCountry,
    c.{{FinCustomer.shippingStreet}} AS shippingStreet,
    NULLIF(concat_ws(', ', NULLIF(c.{{FinCustomer.shippingCity}}, ''),
        NULLIF(concat_ws(' ', NULLIF(c.{{FinCustomer.shippingState}}, ''),
            NULLIF(c.{{FinCustomer.shippingPostalCode}}, '')), '')), '') AS shippingCityLine,
    c.{{FinCustomer.shippingCountry}} AS shippingCountry,
    c.{{FinCustomer.contactEmail}} AS contactEmail,
    i.{{FinInvoice.description}}  AS description,
    i.{{FinInvoice.subtotal}}     AS subtotal,
    i.{{FinInvoice.taxTotal}}     AS taxTotal,
    i.{{FinInvoice.total}}        AS total
FROM {{FinInvoice}} i
LEFT JOIN {{FinCustomer}} c ON c.{{FinCustomer.customerCode}} = i.{{FinInvoice.customerCode}}
LEFT JOIN {{FinPaymentTerms}} t ON t.{{FinPaymentTerms.termsCode}} = i.{{FinInvoice.termsCode}}
LEFT JOIN {{FinInvoice}} o ON o.{{FinInvoice.invoiceId}} = i.{{FinInvoice.originalInvoiceId}}
WHERE i.{{FinInvoice.invoiceId}} = :invoiceId
