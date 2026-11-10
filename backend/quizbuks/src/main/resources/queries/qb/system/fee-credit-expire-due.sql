/*---
id: qb.system.fee-credit-expire-due
description: The lots of platform fee Kudos expired with something left (the items of the daily job qb.fee-credit-expire, F-M2)
entities: [QbFeeCreditGrant]
datasets:
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
params:
  at: { like: QbFeeCreditGrant.expiresAt, required: true, description: "The run's time: a lot valid until then or before has expired" }
results:
  grantId: { from: QbFeeCreditGrant.grantId }
list:
  sorts: [grantId]
  defaultSort: { field: grantId, asc: true }
  key: [grantId]
permissions: [qb.fee.system]
---*/
-- The item voids what is left under the sponsor's wallet lock, reading the lot again (a payment may have spent it since).
SELECT g.{{QbFeeCreditGrant.grantId}} AS grantId
FROM {{QbFeeCreditGrant}} g
WHERE g.{{QbFeeCreditGrant.remaining}} > 0
  AND g.{{QbFeeCreditGrant.expiresAt}} <= :at
