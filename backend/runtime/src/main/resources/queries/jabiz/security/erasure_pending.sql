/*---
id: jabiz.erasure_pending
description: >-
  The erasures whose purge has not run yet (decision D62 item 3): the job jabiz.erasure-purge runs ERASURE_PURGE for
  each, so that a purge whose event was given up on (no maintenance connection yet, a failure) still happens.
entities: [Erasure, ErasurePurge]
results:
  erasureId:  { from: Erasure.erasureId }
  erasedTime: { from: Erasure.erasedTime }
list:
  sorts: [erasedTime]
  defaultSort: { field: erasedTime, asc: true }
  key: [erasureId]
permissions: [security.user.erase]
---*/
SELECT e.{{Erasure.erasureId}} AS erasureId, e.{{Erasure.erasedTime}} AS erasedTime
FROM {{Erasure}} e
WHERE NOT EXISTS (SELECT 1 FROM {{ErasurePurge}} p WHERE p.{{ErasurePurge.erasureId}} = e.{{Erasure.erasureId}})
