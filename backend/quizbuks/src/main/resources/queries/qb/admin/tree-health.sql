/*---
id: qb.admin.tree-health
description: "F-S2b2: what is wrong with the knowledge maps' lighting and review, one row a kind of problem: passes of bound publications that lit nothing (the daily recheck evaluates them), lit records naming no node, versions waiting for review past the reminder, gate parameters naming no node to light (F-S2c), nodes in effect naming a node no single graph has (F-S2d); empty when all is well"
entities: [QbAttempt, QbPublicationCard, QbTree, QbTreeNode, QbTreeNodeRef, QbTreeLit, QbTreeLitRevocation, QbTreeVersion, SysParam]
datasets:
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeNodeRef: urn:jabiz:dataset:default:QbTreeNodeRef
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time checked for }
results:
  checkCode: { kind: { type: text, maxLength: 30 } }
  found:     { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [checkCode]
  defaultSort: { field: checkCode, asc: true }
  key: [checkCode]
permissions: [qb.admin.trees.read]
---*/
-- One statement, one snapshot. The daily job QB_TREE_CHECK_RUN asks the platform with a task when it returns anything.
-- UNLIT_PASS: as qb.system.tree-recheck-due (the same conditions), counted.
SELECT CAST('UNLIT_PASS' AS varchar(30)) AS checkCode, CAST(count(*) AS numeric) AS found
FROM (
    SELECT DISTINCT a.{{QbAttempt.userId}}, c.{{QbPublicationCard.treeId}}, c.{{QbPublicationCard.treeNodeId}}
    FROM {{QbPublicationCard}} c
    JOIN {{QbTreeNode}} tn ON tn.{{QbTreeNode.treeId}} = c.{{QbPublicationCard.treeId}}
        AND tn.{{QbTreeNode.nodeId}} = c.{{QbPublicationCard.treeNodeId}} AND tn.{{QbTreeNode.active}}
    JOIN {{QbAttempt}} a ON a.{{QbAttempt.publicationId}} = c.{{QbPublicationCard.publicationId}}
        AND a.{{QbAttempt.passed}} AND a.{{QbAttempt.status}} <> 'IN_PROGRESS'
    WHERE c.{{QbPublicationCard.treeNodeId}} IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM {{QbTreeLit}} l
          WHERE l.{{QbTreeLit.subjectKind}} = CASE WHEN a.{{QbAttempt.onBehalfOf}} IS NULL THEN 'USER' ELSE 'SPONSOR' END
            AND l.{{QbTreeLit.subjectId}} = a.{{QbAttempt.userId}}
            AND l.{{QbTreeLit.treeId}} = c.{{QbPublicationCard.treeId}}
            AND l.{{QbTreeLit.nodeId}} = c.{{QbPublicationCard.treeNodeId}}
            AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                            WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text)))
      AND a.{{QbAttempt.submittedAt}} > coalesce((
          SELECT max(r.{{QbTreeLitRevocation.revokedAt}})
          FROM {{QbTreeLitRevocation}} r
          JOIN {{QbTreeLit}} l ON CAST(l.{{QbTreeLit.litId}} AS text) = r.{{QbTreeLitRevocation.litId}}
          WHERE l.{{QbTreeLit.subjectKind}} = CASE WHEN a.{{QbAttempt.onBehalfOf}} IS NULL THEN 'USER' ELSE 'SPONSOR' END
            AND l.{{QbTreeLit.subjectId}} = a.{{QbAttempt.userId}}
            AND l.{{QbTreeLit.treeId}} = c.{{QbPublicationCard.treeId}}
            AND l.{{QbTreeLit.nodeId}} = c.{{QbPublicationCard.treeNodeId}}), '-infinity')
) missing
HAVING count(*) > 0
UNION ALL
-- ORPHAN_LIT: a lit record whose graph has no such node (the rows are never deleted: data written around the processes).
SELECT CAST('ORPHAN_LIT' AS varchar(30)), CAST(count(*) AS numeric)
FROM {{QbTreeLit}} l
WHERE NOT EXISTS (SELECT 1 FROM {{QbTreeNode}} tn
                  WHERE tn.{{QbTreeNode.treeId}} = l.{{QbTreeLit.treeId}} AND tn.{{QbTreeNode.nodeId}} = l.{{QbTreeLit.nodeId}})
HAVING count(*) > 0
UNION ALL
-- REVIEW_OVERDUE: versions waiting for their review longer than qb.tree.review.remind-after days (the backlog).
SELECT CAST('REVIEW_OVERDUE' AS varchar(30)), CAST(count(*) AS numeric)
FROM {{QbTreeVersion}} v
WHERE v.{{QbTreeVersion.status}} = 'REVIEW'
  AND v.{{QbTreeVersion.submittedAt}} <= CAST(:at AS timestamptz) - make_interval(days => (
      SELECT CAST(max(p.{{SysParam.value}}) AS int) FROM {{SysParam}} p
      WHERE p.{{SysParam.paramKey}} = 'qb.tree.review.remind-after' AND p.{{SysParam.value}} ~ '^[0-9]{1,4}$'))
HAVING count(*) > 0
UNION ALL
-- GATE_INVALID (F-S2c): a gate parameter that names no node to light (tree.TreeGates.names: a node in effect of a
-- platform map Takers light, as <treeId>/<nodeId>; F-S4b: for the reward methods' gates, TreeGates.namesForSponsors:
-- of a platform template Sponsors light): the feature it opens is closed to everyone.
SELECT CAST('GATE_INVALID' AS varchar(30)), CAST(count(*) AS numeric)
FROM {{SysParam}} p
WHERE p.{{SysParam.paramKey}} IN ('qb.tree.gate.sponsor-apply-node', 'qb.tree.gate.referrer-node',
                                  'qb.tree.gate.benefit-provider-node', 'qb.tree.gate.benefit-sponsor-node',
                                  'qb.tree.gate.digital-prize-node', 'qb.tree.gate.offline-redeem-node')
  AND NOT EXISTS (
      SELECT 1 FROM {{QbTree}} t
      JOIN {{QbTreeNode}} tn ON tn.{{QbTreeNode.treeId}} = CAST(t.{{QbTree.treeId}} AS text)
      WHERE p.{{SysParam.value}} ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND CAST(t.{{QbTree.treeId}} AS text) = split_part(p.{{SysParam.value}}, '/', 1)
        AND tn.{{QbTreeNode.nodeId}} = split_part(p.{{SysParam.value}}, '/', 2)
        AND t.{{QbTree.ownerKind}} = 'PLATFORM'
        AND t.{{QbTree.subjectKind}} = CASE WHEN p.{{SysParam.paramKey}} IN ('qb.tree.gate.digital-prize-node',
                                            'qb.tree.gate.offline-redeem-node') THEN 'SPONSOR' ELSE 'TAKER' END
        AND t.{{QbTree.status}} = 'ACTIVE' AND tn.{{QbTreeNode.active}})
HAVING count(*) > 0
UNION ALL
-- BEFORE_ANSWER_UNRESOLVED (F-S2d): a node in effect whose condition names a node that no single graph it may name has
-- (tree.TreeRules#resolve when its version went into effect, or the migration that wrote the graphs in): that node is
-- never counted as lit, so the condition cannot hold and a publication needing it before answering is hidden.
SELECT CAST('BEFORE_ANSWER_UNRESOLVED' AS varchar(30)), CAST(count(*) AS numeric)
FROM {{QbTreeNode}} tn
WHERE tn.{{QbTreeNode.active}}
  AND (EXISTS (SELECT 1 FROM {{QbTreeNodeRef}} r
               WHERE r.{{QbTreeNodeRef.treeId}} = tn.{{QbTreeNode.treeId}} AND r.{{QbTreeNodeRef.nodeId}} = tn.{{QbTreeNode.nodeId}}
                 AND r.{{QbTreeNodeRef.refTreeId}} IS NULL)
       OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(CAST(coalesce(tn.{{QbTreeNode.beforeAnswer}}, '[]') AS jsonb)) AS ba(node)
                  WHERE strpos(ba.node, '/') = 0))
HAVING count(*) > 0
