/*---
id: qb.admin.tree-version
description: "F-S2b3a: one version of a knowledge map node by node, as its reviewer compares it with the version in effect: ADDED, CHANGED (name, description or condition) or SAME, and REMOVED for the nodes in effect it drops; with how many running publications are bound to each node (a removed node's would no longer start)"
entities: [QbTreeVersion, QbTreeNodeDef, QbTree, QbPublicationCard, QbTreeNodeCount, SysParam]
datasets:
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNodeDef: urn:jabiz:dataset:default:QbTreeNodeDef
  QbTree: urn:jabiz:dataset:default:QbTree
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbTreeNodeCount: urn:jabiz:dataset:default:QbTreeNodeCount
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  versionId: { like: QbTreeVersion.versionId, required: true, description: The version }
results:
  nodeId:       { from: QbTreeNodeDef.nodeId }
  change:       { kind: { type: text, maxLength: 10 } }
  name:         { kind: { type: text } }
  description:  { kind: { type: text } }
  condition:    { kind: { type: text } }
  seq:          { kind: { type: numeric, precision: 4, scale: 0 } }
  runningBound: { kind: { type: numeric, precision: 9, scale: 0 } }
  answeredUnlit: { kind: { type: numeric, precision: 9, scale: 0 } }
  countedOn:    { from: QbTreeNodeCount.countedOn }
list:
  sorts: [seq]
  defaultSort: { field: seq, asc: true }
  filters: [change]
  key: [nodeId]
permissions: [qb.admin.trees.read]
---*/
-- The version in effect is the map's current one (none: every node of the version is ADDED). A removed node's
-- definition is the one in effect; it sorts after the version's own nodes.
WITH v AS (
    SELECT v.{{QbTreeVersion.versionId}} AS versionId, v.{{QbTreeVersion.treeId}} AS treeId,
           t.{{QbTree.currentVersionId}} AS effectId
    FROM {{QbTreeVersion}} v
    JOIN {{QbTree}} t ON t.{{QbTree.treeId}} = v.{{QbTreeVersion.treeId}}
    WHERE v.{{QbTreeVersion.versionId}} = CAST(:versionId AS text)
),
-- F-S3f: the daily count of the people who answered and have not lit (qb.tree-node-counts) as of countedOn, from
-- the statistics' minimum group up (the same k as qb.sponsor.tree); a removed node has none.
k AS (
    SELECT greatest(3, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,9}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 5)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.stats.min-group'
),
running AS (
    SELECT c.{{QbPublicationCard.treeNodeId}} AS nodeId, count(*) AS n
    FROM {{QbPublicationCard}} c
    JOIN v ON c.{{QbPublicationCard.treeId}} = v.treeId
    WHERE c.{{QbPublicationCard.status}} IN ('SCHEDULED', 'LIVE', 'PAUSED', 'FUNDS_SHORT')
    GROUP BY c.{{QbPublicationCard.treeNodeId}}
)
SELECT d.{{QbTreeNodeDef.nodeId}} AS nodeId,
       CASE WHEN e.{{QbTreeNodeDef.nodeId}} IS NULL THEN 'ADDED'
            WHEN CAST(e.{{QbTreeNodeDef.name}} AS jsonb) IS DISTINCT FROM CAST(d.{{QbTreeNodeDef.name}} AS jsonb)
              OR CAST(e.{{QbTreeNodeDef.description}} AS jsonb) IS DISTINCT FROM CAST(d.{{QbTreeNodeDef.description}} AS jsonb)
              OR CAST(e.{{QbTreeNodeDef.condition}} AS jsonb) IS DISTINCT FROM CAST(d.{{QbTreeNodeDef.condition}} AS jsonb)
            THEN 'CHANGED' ELSE 'SAME' END AS change,
       CAST(d.{{QbTreeNodeDef.name}} AS text) AS name, CAST(d.{{QbTreeNodeDef.description}} AS text) AS description,
       d.{{QbTreeNodeDef.condition}} AS condition, d.{{QbTreeNodeDef.seq}} AS seq,
       coalesce(r.n, 0) AS runningBound,
       CASE WHEN nc.{{QbTreeNodeCount.answeredUnlit}} >= k.k THEN nc.{{QbTreeNodeCount.answeredUnlit}} END
           AS answeredUnlit,
       nc.{{QbTreeNodeCount.countedOn}} AS countedOn
FROM v
CROSS JOIN k
JOIN {{QbTreeNodeDef}} d ON d.{{QbTreeNodeDef.versionId}} = v.versionId
LEFT JOIN {{QbTreeNodeDef}} e ON e.{{QbTreeNodeDef.versionId}} = v.effectId
    AND e.{{QbTreeNodeDef.nodeId}} = d.{{QbTreeNodeDef.nodeId}}
LEFT JOIN running r ON r.nodeId = d.{{QbTreeNodeDef.nodeId}}
LEFT JOIN {{QbTreeNodeCount}} nc ON nc.{{QbTreeNodeCount.treeId}} = v.treeId
    AND nc.{{QbTreeNodeCount.nodeId}} = d.{{QbTreeNodeDef.nodeId}}
UNION ALL
SELECT e.{{QbTreeNodeDef.nodeId}}, 'REMOVED', CAST(e.{{QbTreeNodeDef.name}} AS text),
       CAST(e.{{QbTreeNodeDef.description}} AS text), e.{{QbTreeNodeDef.condition}},
       10000 + e.{{QbTreeNodeDef.seq}}, coalesce(r.n, 0), CAST(NULL AS numeric(9, 0)), CAST(NULL AS date)
FROM v
JOIN {{QbTreeNodeDef}} e ON e.{{QbTreeNodeDef.versionId}} = v.effectId AND v.effectId <> v.versionId
LEFT JOIN running r ON r.nodeId = e.{{QbTreeNodeDef.nodeId}}
WHERE NOT EXISTS (SELECT 1 FROM {{QbTreeNodeDef}} d
                  WHERE d.{{QbTreeNodeDef.versionId}} = v.versionId
                    AND d.{{QbTreeNodeDef.nodeId}} = e.{{QbTreeNodeDef.nodeId}})
