/*---
id: qb.system.usage-sponsors
description: >-
  Every sponsor with an onboarding record, whatever its review (the items of the daily job qb.usage-snapshot, F-C2);
  an erased account's record is a tombstone and is not listed
entities: [QbSponsor]
datasets:
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
results:
  sponsorId: { from: QbSponsor.userId }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.usage.system]
---*/
-- Each item measures one sponsor (QB_USAGE_SNAPSHOT_ITEM); a sponsor with nothing yet gets a day of zeros.
SELECT DISTINCT s.{{QbSponsor.userId}} AS sponsorId
FROM {{QbSponsor}} s
