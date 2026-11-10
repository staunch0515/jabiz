/*---
id: qb.system.tree-grants-due
description: The Sponsors whose lighting grants are to be settled (the items of the daily job qb.tree-grant-retry, F-S4b) - a template node they hold lit with no lot yet, or a lot whose node they no longer hold lit with something left
entities: [QbTreeLit, QbTreeLitRevocation, QbTree, QbFeeCreditGrant]
datasets:
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  QbTree: urn:jabiz:dataset:default:QbTree
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
results:
  sponsorId: { from: QbTreeLit.subjectId }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.fee.system]
---*/
-- The same rule as fee.LightGrants#settle, which the item runs again under the Sponsor's wallet lock. A lot's key is
-- the template's node (light:<template>/<node>): a copy's lit record counts for its template's node. While the
-- grants' switch is off (or the Sponsor is banned or not approved) the item grants nothing and the Sponsor is listed
-- again.
WITH held AS (
    SELECT l.{{QbTreeLit.subjectId}} AS r_party,
           'light:' || CASE WHEN t.{{QbTree.ownerKind}} = 'PLATFORM' THEN CAST(t.{{QbTree.treeId}} AS text)
                      ELSE t.{{QbTree.templateTreeId}} END || '/' || l.{{QbTreeLit.nodeId}} AS r_key
    FROM {{QbTreeLit}} l
    JOIN {{QbTree}} t ON CAST(t.{{QbTree.treeId}} AS text) = l.{{QbTreeLit.treeId}}
    WHERE l.{{QbTreeLit.subjectKind}} = 'SPONSOR' AND l.{{QbTreeLit.grantAmount}} > 0
      AND (t.{{QbTree.ownerKind}} = 'PLATFORM' OR t.{{QbTree.templateTreeId}} IS NOT NULL)
      AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                      WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))
)
SELECT DISTINCT h.r_party AS sponsorId
FROM held h
WHERE NOT EXISTS (SELECT 1 FROM {{QbFeeCreditGrant}} g
                  WHERE g.{{QbFeeCreditGrant.sponsorId}} = h.r_party AND g.{{QbFeeCreditGrant.sourceKey}} = h.r_key)
UNION
SELECT DISTINCT g.{{QbFeeCreditGrant.sponsorId}}
FROM {{QbFeeCreditGrant}} g
WHERE g.{{QbFeeCreditGrant.source}} = 'LIGHT' AND g.{{QbFeeCreditGrant.remaining}} > 0
  AND NOT EXISTS (SELECT 1 FROM held h
                  WHERE h.r_party = g.{{QbFeeCreditGrant.sponsorId}} AND h.r_key = g.{{QbFeeCreditGrant.sourceKey}})
