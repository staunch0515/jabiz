/*---
id: jabiz.account_deletion_due
description: >-
  The requests to delete an account that are pending and due by the given time (docs/design/10-security.md section
  17.5): the job jabiz.account-deletion runs SEC_ACCOUNT_DELETION_EXECUTE for each, oldest first.
entities: [AccountDeletion, AccountDeletionEnd]
params:
  dueBy: { like: AccountDeletion.dueTime, required: true, description: Requests due at or before this time }
results:
  requestId: { from: AccountDeletion.requestId }
  dueTime:   { from: AccountDeletion.dueTime }
list:
  sorts: [dueTime]
  defaultSort: { field: dueTime, asc: true }
  key: [requestId]
permissions: [security.user.erase]
---*/
SELECT d.{{AccountDeletion.requestId}} AS requestId, d.{{AccountDeletion.dueTime}} AS dueTime
FROM {{AccountDeletion}} d
WHERE d.{{AccountDeletion.dueTime}} <= :dueBy
  AND NOT EXISTS (SELECT 1 FROM {{AccountDeletionEnd}} e
                  WHERE e.{{AccountDeletionEnd.requestId}} = d.{{AccountDeletion.requestId}})
