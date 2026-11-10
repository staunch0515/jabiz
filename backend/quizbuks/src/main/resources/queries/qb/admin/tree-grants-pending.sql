/*---
id: qb.admin.tree-grants-pending
description: The lighting grants still to be given (F-S4b) - each lit record with a grant, not revoked, whose lot of platform fee Kudos was not granted yet (the job qb.tree-grant-retry gives them; while the grants' switch is off, it does not)
entities: [QbTreeLit, QbTreeLitRevocation, QbFeeCreditGrant]
datasets:
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
results:
  litId:       { from: QbTreeLit.litId }
  sponsorId:   { from: QbTreeLit.subjectId }
  treeId:      { from: QbTreeLit.treeId }
  nodeId:      { from: QbTreeLit.nodeId }
  litAt:       { from: QbTreeLit.litAt }
  grantAmount: { from: QbTreeLit.grantAmount }
list:
  filters: [sponsorId, treeId, litAt]
  sorts: [litAt, sponsorId]
  defaultSort: { field: litAt, asc: true }
  key: [litId]
permissions: [qb.admin.finance.read]
---*/
-- The same rule as qb.system.tree-grants-due, one row per lit record.
SELECT l.{{QbTreeLit.litId}} AS litId, l.{{QbTreeLit.subjectId}} AS sponsorId, l.{{QbTreeLit.treeId}} AS treeId,
       l.{{QbTreeLit.nodeId}} AS nodeId, l.{{QbTreeLit.litAt}} AS litAt, l.{{QbTreeLit.grantAmount}} AS grantAmount
FROM {{QbTreeLit}} l
WHERE l.{{QbTreeLit.subjectKind}} = 'SPONSOR' AND l.{{QbTreeLit.grantAmount}} > 0
  AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                  WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeCreditGrant}} g
                  WHERE g.{{QbFeeCreditGrant.sponsorId}} = l.{{QbTreeLit.subjectId}}
                    AND g.{{QbFeeCreditGrant.sourceKey}} = 'lit:' || CAST(l.{{QbTreeLit.litId}} AS text))
