/*---
id: qb.admin.tree-health
description: "F-S2b2: what is wrong with the knowledge maps' lighting and review, one row a kind of problem: passes of bound publications that lit nothing (the daily recheck evaluates them), lit records naming no node, versions waiting for review past the reminder; empty when all is well"
entities: [QbAttempt, QbPublicationCard, QbTreeNode, QbTreeLit, QbTreeLitRevocation, QbTreeVersion, SysParam]
datasets:
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
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
          WHERE l.{{QbTreeLit.subjectKind}} = 'USER' AND l.{{QbTreeLit.subjectId}} = a.{{QbAttempt.userId}}
            AND l.{{QbTreeLit.treeId}} = c.{{QbPublicationCard.treeId}}
            AND l.{{QbTreeLit.nodeId}} = c.{{QbPublicationCard.treeNodeId}}
            AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                            WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text)))
      AND a.{{QbAttempt.submittedAt}} > coalesce((
          SELECT max(r.{{QbTreeLitRevocation.revokedAt}})
          FROM {{QbTreeLitRevocation}} r
          JOIN {{QbTreeLit}} l ON CAST(l.{{QbTreeLit.litId}} AS text) = r.{{QbTreeLitRevocation.litId}}
          WHERE l.{{QbTreeLit.subjectKind}} = 'USER' AND l.{{QbTreeLit.subjectId}} = a.{{QbAttempt.userId}}
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
