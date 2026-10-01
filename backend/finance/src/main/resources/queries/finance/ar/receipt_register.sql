/*---
id: finance.ar.receipt_register
description: >-
  Customer receipts dated in a range (FIN-UI-004): number, customer, date, method, reference, bank account, amount,
  what is still unapplied and the state. Run with knownAt, the register as recorded at that time.
entities: [FinReceipt, FinCustomer]
params:
  from:         { like: FinReceipt.receiptDate, required: true, description: first receipt date }
  to:           { like: FinReceipt.receiptDate, required: true, description: last receipt date }
  customerCode: { like: FinReceipt.customerCode, description: "only this customer's, if given" }
  status:       { like: FinReceipt.status, description: "only receipts in this state, if given" }
results:
  receiptId:       { from: FinReceipt.receiptId }
  receiptNo:       { from: FinReceipt.receiptNo }
  receiptDate:     { from: FinReceipt.receiptDate }
  customerCode:    { from: FinReceipt.customerCode }
  customerName:    { from: FinCustomer.legalName }
  method:          { from: FinReceipt.method }
  reference:       { from: FinReceipt.reference }
  bankAccount:     { from: FinReceipt.bankAccount }
  currency:        { from: FinReceipt.currency }
  amount:          { from: FinReceipt.amount }
  unappliedAmount: { from: FinReceipt.unappliedAmount }
  status:          { from: FinReceipt.status }
list:
  filters: [receiptNo, customerCode, method, bankAccount, status]
  sorts:   [receiptNo, receiptDate, customerCode, amount, unappliedAmount]
  defaultSort: { field: receiptDate, asc: true }
  key: [receiptId]
permissions: [fin.ar.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
SELECT
    r.{{FinReceipt.receiptId}}       AS receiptId,
    r.{{FinReceipt.receiptNo}}       AS receiptNo,
    r.{{FinReceipt.receiptDate}}     AS receiptDate,
    r.{{FinReceipt.customerCode}}    AS customerCode,
    c.{{FinCustomer.legalName}}      AS customerName,
    r.{{FinReceipt.method}}          AS method,
    r.{{FinReceipt.reference}}       AS reference,
    r.{{FinReceipt.bankAccount}}     AS bankAccount,
    r.{{FinReceipt.currency}}        AS currency,
    r.{{FinReceipt.amount}}          AS amount,
    r.{{FinReceipt.unappliedAmount}} AS unappliedAmount,
    r.{{FinReceipt.status}}          AS status
FROM {{FinReceipt}} r
LEFT JOIN {{FinCustomer}} c ON c.{{FinCustomer.customerCode}} = r.{{FinReceipt.customerCode}}
WHERE r.{{FinReceipt.receiptDate}} BETWEEN :from AND :to
  AND (CAST(:customerCode AS text) IS NULL OR r.{{FinReceipt.customerCode}} = :customerCode)
  AND (CAST(:status AS text) IS NULL OR r.{{FinReceipt.status}} = :status)
