/*---
id: qb.system.mail-fee-paid-due
description: Sponsors with invoices paid from their platform fee account that the last mail did not tell, whose last mail was sent before the given time, oldest first (the items of the job qb.mail-fee-paid-due)
entities: [QbMailNotice]
datasets:
  QbMailNotice: urn:jabiz:dataset:default:QbMailNotice
params:
  before: { like: QbMailNotice.lastSentAt, required: true, description: "The run's time less the window (the last mail went by then)" }
results:
  sponsorId:  { from: QbMailNotice.recipientId }
  lastSentAt: { from: QbMailNotice.lastSentAt }
list:
  sorts: [lastSentAt]
  defaultSort: { field: lastSentAt, asc: true }
  key: [sponsorId]
permissions: [qb.messaging.system]
---*/
-- Through the partial index on the pending rows (V13). QB_MAIL_FEE_PAID_DUE reads the row again under the sponsor's
-- notices' lock and tells the payments from its time up to the run's cut.
SELECT n.{{QbMailNotice.recipientId}} AS sponsorId, n.{{QbMailNotice.lastSentAt}} AS lastSentAt
FROM {{QbMailNotice}} n
WHERE n.{{QbMailNotice.pending}} AND n.{{QbMailNotice.kind}} = 'FEE_AUTO_PAID'
  AND n.{{QbMailNotice.lastSentAt}} <= :before
