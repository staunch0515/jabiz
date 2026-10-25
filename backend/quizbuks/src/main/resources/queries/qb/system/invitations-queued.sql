/*---
id: qb.system.invitations-queued
description: The invitations whose mail waits to be sent, oldest first (the items of the job qb.invitation-mail)
entities: [QbInvitation]
datasets:
  QbInvitation: urn:jabiz:dataset:default:QbInvitation
results:
  invitationId: { from: QbInvitation.invitationId }
  addedAt:      { from: QbInvitation.addedAt }
list:
  sorts: [addedAt]
  defaultSort: { field: addedAt, asc: true }
  key: [invitationId]
permissions: [qb.publication.system]
---*/
-- The job's process reads the invitation again under its list's lock and leaves one removed or sent meanwhile.
SELECT i.{{QbInvitation.invitationId}} AS invitationId, i.{{QbInvitation.addedAt}} AS addedAt
FROM {{QbInvitation}} i
WHERE i.{{QbInvitation.status}} = 'QUEUED'
