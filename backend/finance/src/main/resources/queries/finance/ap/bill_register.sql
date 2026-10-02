/*---
id: finance.ap.bill_register
description: >-
  Bills and vendor credits dated in a range (FIN-UI-004): number, kind, vendor and the vendor's invoice number, dates,
  totals, use tax, what is open and the approval, with vendor credits negative so that the amounts add up. Drafts only
  when asked for by their state. Run with knownAt, the register as recorded at that time.
entities: [FinBill, FinVendor]
params:
  from:       { like: FinBill.invoiceDate, required: true, description: first invoice date }
  to:         { like: FinBill.invoiceDate, required: true, description: last invoice date }
  vendorCode: { like: FinBill.vendorCode, description: "only this vendor's, if given" }
  status:     { like: FinBill.status, description: "only documents in this state, if given; drafts only so" }
  kind:       { like: FinBill.kind, description: "only bills or only vendor credits, if given" }
  approval:   { like: FinBill.approval, description: "only documents in this approval state, if given" }
results:
  billId:          { from: FinBill.billId }
  billNo:          { from: FinBill.billNo }
  kind:            { from: FinBill.kind }
  vendorCode:      { from: FinBill.vendorCode }
  vendorName:      { from: FinVendor.legalName }
  vendorInvoiceNo: { from: FinBill.vendorInvoiceNo }
  invoiceDate:     { from: FinBill.invoiceDate }
  postingDate:     { from: FinBill.postingDate }
  dueDate:         { from: FinBill.dueDate }
  currency:        { from: FinBill.currency }
  total:           { from: FinBill.total }
  useTaxTotal:     { from: FinBill.useTaxTotal }
  openAmount:      { from: FinBill.openAmount }
  totalUsd:        { from: FinBill.totalUsd }
  openAmountUsd:   { from: FinBill.openAmountUsd }
  status:          { from: FinBill.status }
  approval:        { from: FinBill.approval }
  glNo:            { from: FinBill.glNo }
list:
  filters: [billNo, kind, vendorCode, vendorInvoiceNo, status, approval, glNo]
  sorts:   [billNo, invoiceDate, dueDate, vendorCode, total, openAmount]
  defaultSort: { field: invoiceDate, asc: true }
  key: [billId]
permissions: [fin.ap.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
SELECT
    b.{{FinBill.billId}}          AS billId,
    b.{{FinBill.billNo}}          AS billNo,
    b.{{FinBill.kind}}            AS kind,
    b.{{FinBill.vendorCode}}      AS vendorCode,
    v.{{FinVendor.legalName}}     AS vendorName,
    b.{{FinBill.vendorInvoiceNo}} AS vendorInvoiceNo,
    b.{{FinBill.invoiceDate}}     AS invoiceDate,
    COALESCE(b.{{FinBill.postingDate}}, b.{{FinBill.invoiceDate}}) AS postingDate,
    b.{{FinBill.dueDate}}         AS dueDate,
    b.{{FinBill.currency}}        AS currency,
    CASE WHEN b.{{FinBill.kind}} = 'CREDIT' THEN -1 ELSE 1 END * b.{{FinBill.total}} AS total,
    CASE WHEN b.{{FinBill.kind}} = 'CREDIT' THEN -1 ELSE 1 END * b.{{FinBill.useTaxTotal}} AS useTaxTotal,
    CASE WHEN b.{{FinBill.kind}} = 'CREDIT' THEN -1 ELSE 1 END * b.{{FinBill.openAmount}} AS openAmount,
    -- In US dollars at the bill's rate (F7); bills from before F7 are in dollars.
    CASE WHEN b.{{FinBill.kind}} = 'CREDIT' THEN -1 ELSE 1 END
        * COALESCE(b.{{FinBill.totalUsd}}, b.{{FinBill.total}}) AS totalUsd,
    CASE WHEN b.{{FinBill.kind}} = 'CREDIT' THEN -1 ELSE 1 END
        * COALESCE(b.{{FinBill.openAmountUsd}}, b.{{FinBill.openAmount}}) AS openAmountUsd,
    b.{{FinBill.status}}          AS status,
    b.{{FinBill.approval}}        AS approval,
    b.{{FinBill.glNo}}            AS glNo
FROM {{FinBill}} b
LEFT JOIN {{FinVendor}} v ON v.{{FinVendor.vendorCode}} = b.{{FinBill.vendorCode}}
WHERE b.{{FinBill.invoiceDate}} BETWEEN :from AND :to
  AND (CAST(:vendorCode AS text) IS NULL OR b.{{FinBill.vendorCode}} = :vendorCode)
  AND (CAST(:kind AS text) IS NULL OR b.{{FinBill.kind}} = :kind)
  AND (CAST(:approval AS text) IS NULL OR b.{{FinBill.approval}} = :approval)
  AND (CASE WHEN CAST(:status AS text) IS NULL THEN b.{{FinBill.status}} <> 'DRAFT'
            ELSE b.{{FinBill.status}} = :status END)
