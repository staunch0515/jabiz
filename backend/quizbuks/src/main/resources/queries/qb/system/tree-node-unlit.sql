/*---
id: qb.system.tree-node-unlit
description: "F-S3f: one graph's nodes in effect, each with the people who finished an attempt at a publication bound to it before the given time and have not lit it now (QB_TREE_NODE_COUNT writes the counts)"
entities: [QbTree, QbTreeNode, QbPublicationCard, QbAttempt, QbTreeLit, QbTreeLitRevocation]
datasets:
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
params:
  treeId: { like: QbTreeNode.treeId, required: true, description: The graph }
  before: { like: QbAttempt.submittedAt, required: true, description: "Attempts submitted before this time count (the end of the day counted)" }
results:
  nodeId:        { from: QbTreeNode.nodeId }
  answeredUnlit: { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [nodeId]
  defaultSort: { field: nodeId, asc: true }
  key: [nodeId]
permissions: [qb.tree.system]
---*/
-- Answered: an attempt that is not in progress (passed or not) at a publication bound to the node; counted by person.
-- Not lit: no lit record of the person for the node without a revocation (tree.TreeLights), as it is now. Every node
-- in effect has a row (none answered: 0), so the job also writes the zeros and removes the nodes no longer in effect.
WITH answered AS (
    SELECT c.{{QbPublicationCard.treeNodeId}} AS nodeId, a.{{QbAttempt.userId}} AS userId
    FROM {{QbPublicationCard}} c
    JOIN {{QbAttempt}} a ON a.{{QbAttempt.publicationId}} = c.{{QbPublicationCard.publicationId}}
        AND a.{{QbAttempt.status}} <> 'IN_PROGRESS' AND a.{{QbAttempt.submittedAt}} < :before
    WHERE c.{{QbPublicationCard.treeId}} = CAST(:treeId AS text) AND c.{{QbPublicationCard.treeNodeId}} IS NOT NULL
    GROUP BY c.{{QbPublicationCard.treeNodeId}}, a.{{QbAttempt.userId}}
)
SELECT n.{{QbTreeNode.nodeId}} AS nodeId,
       CAST((SELECT count(*) FROM answered x
             WHERE x.nodeId = n.{{QbTreeNode.nodeId}}
               AND NOT EXISTS (
                   SELECT 1 FROM {{QbTreeLit}} l
                   -- F-S4c: a map Sponsors light is lit for the Sponsor (subject SPONSOR, the attempt's user), others for the user.
                   WHERE l.{{QbTreeLit.subjectKind}} = (SELECT CASE t.{{QbTree.subjectKind}} WHEN 'SPONSOR'
                                                             THEN 'SPONSOR' ELSE 'USER' END
                                                       FROM {{QbTree}} t
                                                       WHERE CAST(t.{{QbTree.treeId}} AS text) = CAST(:treeId AS text))
                     AND l.{{QbTreeLit.subjectId}} = x.userId
                     AND l.{{QbTreeLit.treeId}} = n.{{QbTreeNode.treeId}}
                     AND l.{{QbTreeLit.nodeId}} = n.{{QbTreeNode.nodeId}}
                     AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                                     WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))))
            AS numeric(9, 0)) AS answeredUnlit
FROM {{QbTreeNode}} n
WHERE n.{{QbTreeNode.treeId}} = CAST(:treeId AS text) AND n.{{QbTreeNode.active}}
