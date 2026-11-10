/*---
id: qb.sponsor.tree
description: "F-S3: one version of one of the caller's own knowledge graphs node by node: the definition (name, description, condition, order and, F-S5b, the certificate names) the editor shows and saves back, whether the node is in effect, how many Takers have lit it (in effect only; under the minimum group, none) and how many publications bound to it are not ended (a node with any cannot be removed)"
entities: [SysParam, QbTree, QbTreeVersion, QbTreeNode, QbTreeNodeDef, QbTreeLit, QbTreeLitRevocation, QbPublicationCard, QbTreeNodeCount]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
  QbTree: urn:jabiz:dataset:sponsor:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeNodeDef: urn:jabiz:dataset:default:QbTreeNodeDef
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbTreeNodeCount: urn:jabiz:dataset:default:QbTreeNodeCount
params:
  versionId: { like: QbTreeVersion.versionId, required: true, description: "A version of the caller's own graph (none for another's)" }
results:
  nodeId:       { from: QbTreeNodeDef.nodeId }
  name:         { kind: { type: text } }
  description:  { kind: { type: text } }
  condition:    { kind: { type: text } }
  certificateNames: { kind: { type: text } }
  seq:          { from: QbTreeNodeDef.seq }
  inEffect:     { kind: { type: bool } }
  litCount:     { kind: { type: numeric, precision: 9, scale: 0 } }
  runningBound: { kind: { type: numeric, precision: 9, scale: 0 } }
  answeredUnlit: { kind: { type: numeric, precision: 9, scale: 0 } }
  countedOn:    { from: QbTreeNodeCount.countedOn }
list:
  sorts: [seq]
  defaultSort: { field: seq, asc: true }
  key: [nodeId]
permissions: [qb.sponsor.me]
---*/
-- The view holds the caller's own graphs only, so another's version has no rows. Lit: a lit record with no revocation
-- (tree.TreeLights), counted by person; a count under the minimum group k is not given (stats.MinGroup: the same
-- greatest(3, … 5) as the analytics templates), so that it never tells one person's progress. F-S3f: answeredUnlit is
-- the daily job's count (qb.tree-node-counts) of the people who answered and have not lit, as of countedOn, under the
-- same minimum group (countedOn stays, so that the page can say when it was counted).
WITH v AS (
    SELECT v.{{QbTreeVersion.versionId}} AS versionId, v.{{QbTreeVersion.treeId}} AS treeId
    FROM {{QbTreeVersion}} v
    JOIN {{QbTree}} t ON CAST(t.{{QbTree.treeId}} AS text) = v.{{QbTreeVersion.treeId}}
    WHERE v.{{QbTreeVersion.versionId}} = CAST(:versionId AS text)
),
k AS (
    SELECT greatest(3, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,9}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 5)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.stats.min-group'
),
lit AS (
    SELECT l.{{QbTreeLit.nodeId}} AS nodeId, count(DISTINCT l.{{QbTreeLit.subjectId}}) AS n
    FROM {{QbTreeLit}} l
    JOIN v ON l.{{QbTreeLit.treeId}} = v.treeId
    WHERE NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                      WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))
    GROUP BY l.{{QbTreeLit.nodeId}}
),
running AS (
    SELECT c.{{QbPublicationCard.treeNodeId}} AS nodeId, count(*) AS n
    FROM {{QbPublicationCard}} c
    JOIN v ON c.{{QbPublicationCard.treeId}} = v.treeId
    WHERE c.{{QbPublicationCard.status}} IN ('SCHEDULED', 'LIVE', 'PAUSED', 'FUNDS_SHORT')
    GROUP BY c.{{QbPublicationCard.treeNodeId}}
)
SELECT d.{{QbTreeNodeDef.nodeId}} AS nodeId,
       CAST(d.{{QbTreeNodeDef.name}} AS text) AS name, CAST(d.{{QbTreeNodeDef.description}} AS text) AS description,
       d.{{QbTreeNodeDef.condition}} AS condition, d.{{QbTreeNodeDef.seq}} AS seq,
       CAST(d.{{QbTreeNodeDef.certificateNames}} AS text) AS certificateNames,
       coalesce(n.{{QbTreeNode.active}}, FALSE) AS inEffect,
       CASE WHEN coalesce(n.{{QbTreeNode.active}}, FALSE) AND coalesce(lit.n, 0) >= k.k THEN lit.n END AS litCount,
       coalesce(running.n, 0) AS runningBound,
       CASE WHEN coalesce(n.{{QbTreeNode.active}}, FALSE) AND nc.{{QbTreeNodeCount.answeredUnlit}} >= k.k
            THEN nc.{{QbTreeNodeCount.answeredUnlit}} END AS answeredUnlit,
       CASE WHEN coalesce(n.{{QbTreeNode.active}}, FALSE) THEN nc.{{QbTreeNodeCount.countedOn}} END AS countedOn
FROM v
CROSS JOIN k
JOIN {{QbTreeNodeDef}} d ON d.{{QbTreeNodeDef.versionId}} = v.versionId
LEFT JOIN {{QbTreeNode}} n ON n.{{QbTreeNode.treeId}} = v.treeId AND n.{{QbTreeNode.nodeId}} = d.{{QbTreeNodeDef.nodeId}}
LEFT JOIN lit ON lit.nodeId = d.{{QbTreeNodeDef.nodeId}}
LEFT JOIN running ON running.nodeId = d.{{QbTreeNodeDef.nodeId}}
LEFT JOIN {{QbTreeNodeCount}} nc ON nc.{{QbTreeNodeCount.treeId}} = v.treeId
    AND nc.{{QbTreeNodeCount.nodeId}} = d.{{QbTreeNodeDef.nodeId}}
