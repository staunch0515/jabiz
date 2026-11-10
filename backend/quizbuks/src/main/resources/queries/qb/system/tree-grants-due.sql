/*---
id: qb.system.tree-grants-due
description: The Sponsors with a lighting grant still due (the items of the daily job qb.tree-grant-retry, F-S4b) - a lit record with a grant, not revoked, whose lot was not granted
entities: [QbTreeLit, QbTreeLitRevocation, QbFeeCreditGrant]
datasets:
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
results:
  sponsorId: { from: QbTreeLit.subjectId }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.fee.system]
---*/
-- The same rule as fee.LightGrants#due, which the item runs again under the Sponsor's wallet lock (a lighting's event may
-- have granted it since). While the grants' switch is off the item grants nothing, and the Sponsor is listed again.
SELECT DISTINCT l.{{QbTreeLit.subjectId}} AS sponsorId
FROM {{QbTreeLit}} l
WHERE l.{{QbTreeLit.subjectKind}} = 'SPONSOR' AND l.{{QbTreeLit.grantAmount}} > 0
  AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                  WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeCreditGrant}} g
                  WHERE g.{{QbFeeCreditGrant.sponsorId}} = l.{{QbTreeLit.subjectId}}
                    AND g.{{QbFeeCreditGrant.sourceKey}} = 'lit:' || CAST(l.{{QbTreeLit.litId}} AS text))
