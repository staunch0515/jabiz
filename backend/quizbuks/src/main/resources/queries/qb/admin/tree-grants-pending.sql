/*---
id: qb.admin.tree-grants-pending
description: The lighting grants still to be given (F-S4b) - each template node a Sponsor holds lit with a grant and no lot of platform fee Kudos yet, by its earliest lit record (the job qb.tree-grant-retry gives them; not while the grants' switch is off, nor to a Sponsor banned or not approved)
entities: [QbTreeLit, QbTreeLitRevocation, QbTree, QbFeeCreditGrant]
datasets:
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  QbTree: urn:jabiz:dataset:default:QbTree
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
results:
  litId:       { from: QbTreeLit.litId }
  sponsorId:   { from: QbTreeLit.subjectId }
  treeId:      { from: QbTreeLit.treeId }
  nodeId:      { from: QbTreeLit.nodeId }
  sourceKey:   { from: QbFeeCreditGrant.sourceKey }
  litAt:       { from: QbTreeLit.litAt }
  grantAmount: { from: QbTreeLit.grantAmount }
list:
  filters: [sponsorId, treeId, litAt]
  sorts: [litAt, sponsorId]
  defaultSort: { field: litAt, asc: true }
  key: [litId]
permissions: [qb.admin.finance.read]
---*/
-- The same rule as fee.LightGrants#settle: one row per Sponsor and template node (its earliest lit record held now).
WITH held AS (
    SELECT l.{{QbTreeLit.litId}} AS r_lit, l.{{QbTreeLit.subjectId}} AS r_party, l.{{QbTreeLit.treeId}} AS r_tree,
           l.{{QbTreeLit.nodeId}} AS r_node, l.{{QbTreeLit.litAt}} AS r_at, l.{{QbTreeLit.grantAmount}} AS r_amount,
           'light:' || CASE WHEN t.{{QbTree.ownerKind}} = 'PLATFORM' THEN CAST(t.{{QbTree.treeId}} AS text)
                      ELSE t.{{QbTree.templateTreeId}} END || '/' || l.{{QbTreeLit.nodeId}} AS r_key
    FROM {{QbTreeLit}} l
    JOIN {{QbTree}} t ON CAST(t.{{QbTree.treeId}} AS text) = l.{{QbTreeLit.treeId}}
    WHERE l.{{QbTreeLit.subjectKind}} = 'SPONSOR' AND l.{{QbTreeLit.grantAmount}} > 0
      AND (t.{{QbTree.ownerKind}} = 'PLATFORM' OR t.{{QbTree.templateTreeId}} IS NOT NULL)
      AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                      WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))
)
SELECT x.r_lit AS litId, x.r_party AS sponsorId, x.r_tree AS treeId, x.r_node AS nodeId,
       CAST(x.r_key AS varchar(100)) AS sourceKey, x.r_at AS litAt, x.r_amount AS grantAmount
FROM (SELECT h.*, row_number() OVER (PARTITION BY h.r_party, h.r_key ORDER BY h.r_at, h.r_lit) AS r_n
      FROM held h
      WHERE NOT EXISTS (SELECT 1 FROM {{QbFeeCreditGrant}} g
                        WHERE g.{{QbFeeCreditGrant.sponsorId}} = h.r_party
                          AND g.{{QbFeeCreditGrant.sourceKey}} = h.r_key)) x
WHERE x.r_n = 1
