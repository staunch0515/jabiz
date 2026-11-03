/*---
id: qb.system.fee-overdue-items
description: "Q87b: the invoices that turn overdue today, and the overdue ones whose next reminder is due (the overdue job's items)"
entities: [QbFeeInvoice]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
params:
  today:    { like: QbFeeInvoice.dueOn, required: true, description: "Today in Japan" }
  remindBy: { like: QbFeeInvoice.issuedAt, required: true, description: "Reminded before this: a reminder is due" }
results:
  invoiceId: { from: QbFeeInvoice.invoiceId }
  sponsorId: { from: QbFeeInvoice.sponsorId }
list:
  sorts: [invoiceId]
  defaultSort: { field: invoiceId, asc: true }
  key: [invoiceId]
permissions: [qb.fee.system]
---*/
SELECT i.{{QbFeeInvoice.invoiceId}} AS invoiceId, i.{{QbFeeInvoice.sponsorId}} AS sponsorId
FROM {{QbFeeInvoice}} i
WHERE (i.{{QbFeeInvoice.status}} = 'ISSUED' AND i.{{QbFeeInvoice.dueOn}} < :today)
   OR (i.{{QbFeeInvoice.status}} = 'OVERDUE' AND i.{{QbFeeInvoice.reminders}} < 3
       AND i.{{QbFeeInvoice.remindedAt}} <= :remindBy)
