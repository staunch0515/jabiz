/*---
id: qb.operator.tree-version
description: "F-S2b3a: qb.admin.tree-version for an operator's member: a version of one of the maps the operator looks after (its copies, its Sponsors' maps), node by node against the version in effect; another operator's version gives no rows"
entities: [QbTreeVersion, QbTreeNodeDef, QbTree, QbPublicationCard, QbTreeNodeCount, QbTreeLit, QbTreeLitRevocation, SysParam, QbOperatorMember, QbSponsorOperator]
datasets:
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNodeDef: urn:jabiz:dataset:default:QbTreeNodeDef
  QbTree: urn:jabiz:dataset:default:QbTree
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbTreeNodeCount: urn:jabiz:dataset:default:QbTreeNodeCount
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  SysParam: urn:jabiz:dataset:now:SysParam
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
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
  litCount:    { kind: { type: numeric, precision: 9, scale: 0 } }
  countedOn:    { from: QbTreeNodeCount.countedOn }
  grantAmount:  { from: QbTreeNodeDef.grantAmount }
list:
  sorts: [seq]
  defaultSort: { field: seq, asc: true }
  filters: [change]
  key: [nodeId]
permissions: [qb.operator.use]
---*/
-- Only a map the caller's operator looks after (the membership dataset holds the caller's own rows). The version in
-- effect is the map's current one (none: every node of the version is ADDED). A removed node's definition is the one
-- in effect; it sorts after the version's own nodes.
WITH v AS (
    SELECT v.{{QbTreeVersion.versionId}} AS versionId, v.{{QbTreeVersion.treeId}} AS treeId,
           t.{{QbTree.currentVersionId}} AS effectId
    FROM {{QbTreeVersion}} v
    JOIN {{QbTree}} t ON t.{{QbTree.treeId}} = v.{{QbTreeVersion.treeId}}
    WHERE v.{{QbTreeVersion.versionId}} = CAST(:versionId AS text)
      AND EXISTS (
            SELECT 1 FROM {{QbOperatorMember}} me
            WHERE me.{{QbOperatorMember.removedAt}} IS NULL AND (
                (t.{{QbTree.ownerKind}} = 'OPERATOR' AND t.{{QbTree.ownerId}} = me.{{QbOperatorMember.operatorId}})
                OR (t.{{QbTree.ownerKind}} = 'SPONSOR' AND EXISTS (
                    SELECT 1 FROM {{QbSponsorOperator}} so
                    WHERE so.{{QbSponsorOperator.userId}} = t.{{QbTree.ownerId}}
                      AND so.{{QbSponsorOperator.operatorId}} = me.{{QbOperatorMember.operatorId}}))))
),
-- F-S3f: the daily count of the people who answered and have not lit (qb.tree-node-counts) as of countedOn, from
-- the statistics' minimum group up (the same k as qb.sponsor.tree); a removed node has none.
k AS (
    SELECT greatest(3, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,9}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 5)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.stats.min-group'
),
-- F-S4c: how many people have the node lit now (a lit record without a revocation, tree.TreeLights), from the
-- minimum group up, as answeredUnlit.
lits AS (
    SELECT l.{{QbTreeLit.nodeId}} AS nodeId, count(DISTINCT l.{{QbTreeLit.subjectId}}) AS n
    FROM {{QbTreeLit}} l
    JOIN v ON l.{{QbTreeLit.treeId}} = CAST(v.treeId AS text)
    WHERE NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} rv
                      WHERE rv.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))
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
       CASE WHEN e.{{QbTreeNodeDef.nodeId}} IS NULL THEN 'ADDED'
            WHEN CAST(e.{{QbTreeNodeDef.name}} AS jsonb) IS DISTINCT FROM CAST(d.{{QbTreeNodeDef.name}} AS jsonb)
              OR CAST(e.{{QbTreeNodeDef.description}} AS jsonb) IS DISTINCT FROM CAST(d.{{QbTreeNodeDef.description}} AS jsonb)
              OR CAST(e.{{QbTreeNodeDef.condition}} AS jsonb) IS DISTINCT FROM CAST(d.{{QbTreeNodeDef.condition}} AS jsonb)
              -- F-S4b: the grant is reviewed with the version.
              OR e.{{QbTreeNodeDef.grantAmount}} IS DISTINCT FROM d.{{QbTreeNodeDef.grantAmount}}
            THEN 'CHANGED' ELSE 'SAME' END AS change,
       CAST(d.{{QbTreeNodeDef.name}} AS text) AS name, CAST(d.{{QbTreeNodeDef.description}} AS text) AS description,
       d.{{QbTreeNodeDef.condition}} AS condition, d.{{QbTreeNodeDef.seq}} AS seq,
       coalesce(r.n, 0) AS runningBound,
       CASE WHEN nc.{{QbTreeNodeCount.answeredUnlit}} >= k.k THEN nc.{{QbTreeNodeCount.answeredUnlit}} END
           AS answeredUnlit,
       nc.{{QbTreeNodeCount.countedOn}} AS countedOn,
       d.{{QbTreeNodeDef.grantAmount}} AS grantAmount,
       CAST(CASE WHEN coalesce(lt.n, 0) >= k.k THEN lt.n END AS numeric(9, 0)) AS litCount
FROM v
CROSS JOIN k
JOIN {{QbTreeNodeDef}} d ON d.{{QbTreeNodeDef.versionId}} = v.versionId
LEFT JOIN {{QbTreeNodeDef}} e ON e.{{QbTreeNodeDef.versionId}} = v.effectId
    AND e.{{QbTreeNodeDef.nodeId}} = d.{{QbTreeNodeDef.nodeId}}
LEFT JOIN running r ON r.nodeId = d.{{QbTreeNodeDef.nodeId}}
LEFT JOIN lits lt ON lt.nodeId = d.{{QbTreeNodeDef.nodeId}}
LEFT JOIN {{QbTreeNodeCount}} nc ON nc.{{QbTreeNodeCount.treeId}} = v.treeId
    AND nc.{{QbTreeNodeCount.nodeId}} = d.{{QbTreeNodeDef.nodeId}}
UNION ALL
SELECT e.{{QbTreeNodeDef.nodeId}}, 'REMOVED', CAST(e.{{QbTreeNodeDef.name}} AS text),
       CAST(e.{{QbTreeNodeDef.description}} AS text), e.{{QbTreeNodeDef.condition}},
       10000 + e.{{QbTreeNodeDef.seq}}, coalesce(r.n, 0), CAST(NULL AS numeric(9, 0)), CAST(NULL AS date),
       e.{{QbTreeNodeDef.grantAmount}}, CAST(NULL AS numeric(9, 0))
FROM v
JOIN {{QbTreeNodeDef}} e ON e.{{QbTreeNodeDef.versionId}} = v.effectId AND v.effectId <> v.versionId
LEFT JOIN running r ON r.nodeId = e.{{QbTreeNodeDef.nodeId}}
WHERE NOT EXISTS (SELECT 1 FROM {{QbTreeNodeDef}} d
                  WHERE d.{{QbTreeNodeDef.versionId}} = v.versionId
                    AND d.{{QbTreeNodeDef.nodeId}} = e.{{QbTreeNodeDef.nodeId}})
