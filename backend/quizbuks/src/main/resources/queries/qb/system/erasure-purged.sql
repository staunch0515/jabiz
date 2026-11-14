/*---
id: qb.system.erasure-purged
description: "When the purge of a user's erasure was done (F-M15 b, re-review L2): ERASURE_PURGE overwrites the declared fields of every row that names the user at that moment, so the wind-up withdraws what was asked for until then; none while the purge is still to run"
entities: [Erasure, ErasurePurge]
datasets:
  Erasure: urn:jabiz:dataset:platform:Erasure
  ErasurePurge: urn:jabiz:dataset:platform:ErasurePurge
params:
  userId: { like: Erasure.userId, required: true, description: The erased sponsor }
results:
  erasedTime: { from: Erasure.erasedTime }
  purgedTime: { from: ErasurePurge.purgedTime }
permissions: [qb.fee.system]
---*/
-- One row an erasure of the user (a user is erased once); purgedTime is null until its purge is done.
SELECT e.{{Erasure.erasedTime}} AS erasedTime, p.{{ErasurePurge.purgedTime}} AS purgedTime
FROM {{Erasure}} e
LEFT JOIN {{ErasurePurge}} p ON p.{{ErasurePurge.erasureId}} = e.{{Erasure.erasureId}}
WHERE CAST(e.{{Erasure.userId}} AS text) = :userId
