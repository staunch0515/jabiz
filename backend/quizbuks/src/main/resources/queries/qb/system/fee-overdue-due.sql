/*---
id: qb.system.fee-overdue-due
description: The platform fee invoices past their due day whose reminder is due (the items of the daily job qb.fee-overdue, Q87)
entities: [QbFeeInvoice]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
params:
  today:          { like: QbFeeInvoice.dueOn, required: true, description: "Today (Japan's day)" }
  remindedBefore: { like: QbFeeInvoice.lastReminderAt, required: true, description: "A reminder sent before this is a week old" }
results:
  invoiceId: { from: QbFeeInvoice.invoiceId }
list:
  sorts: [invoiceId]
  defaultSort: { field: invoiceId, asc: true }
  key: [invoiceId]
permissions: [qb.fee.system]
---*/
-- Open, partly paid or overdue, due before today, fewer than three reminders, the last one a week ago or more (or none). The item
-- decides again under the sponsor's wallet lock.
SELECT i.{{QbFeeInvoice.invoiceId}} AS invoiceId
FROM {{QbFeeInvoice}} i
WHERE i.{{QbFeeInvoice.status}} IN ('OPEN', 'PARTIAL', 'OVERDUE')
  AND i.{{QbFeeInvoice.dueOn}} < :today
  AND i.{{QbFeeInvoice.remindersSent}} < 3
  AND (i.{{QbFeeInvoice.lastReminderAt}} IS NULL OR i.{{QbFeeInvoice.lastReminderAt}} <= :remindedBefore)
