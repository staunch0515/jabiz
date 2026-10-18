/*---
id: qb.system.mail-funds-short-due
description: Sponsors whose short-of-funds mail was deferred within the day and whose day has passed by the given time, oldest first (the items of the job qb.mail-funds-short-due)
entities: [QbMailNotice]
datasets:
  QbMailNotice: urn:jabiz:dataset:default:QbMailNotice
params:
  before: { like: QbMailNotice.lastSentAt, required: true, description: "The run's time less a day (the last mail went by then)" }
results:
  sponsorId:  { from: QbMailNotice.recipientId }
  lastSentAt: { from: QbMailNotice.lastSentAt }
list:
  sorts: [lastSentAt]
  defaultSort: { field: lastSentAt, asc: true }
  key: [sponsorId]
permissions: [qb.messaging.system]
---*/
-- Through the partial index on the pending rows (V13). QB_MAIL_FUNDS_SHORT_DUE reads the row again under the sponsor's
-- notices' lock and mails only while it is still pending and some publication is still short of funds.
SELECT n.{{QbMailNotice.recipientId}} AS sponsorId, n.{{QbMailNotice.lastSentAt}} AS lastSentAt
FROM {{QbMailNotice}} n
WHERE n.{{QbMailNotice.pending}} AND n.{{QbMailNotice.kind}} = 'FUNDS_SHORT'
  AND n.{{QbMailNotice.lastSentAt}} <= :before
