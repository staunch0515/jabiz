/*---
id: qb.system.tree-recheck-due
description: "F-S2b2: the people who passed a publication bound to a knowledge map's node in effect and have not lit the node (the event was lost or its evaluation failed): the items of the daily job qb.tree-recheck, which evaluates each again"
entities: [QbAttempt, QbPublicationCard, QbTreeNode, QbTreeLit, QbTreeLitRevocation]
datasets:
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
results:
  userId: { from: QbAttempt.userId }
  treeId: { from: QbPublicationCard.treeId }
  nodeId: { from: QbPublicationCard.treeNodeId }
list:
  sorts: [userId]
  defaultSort: { field: userId, asc: true }
  key: [userId, treeId, nodeId]
permissions: [qb.tree.system]
---*/
-- A pass counts only after the person's last revoked lighting of the node (tree.TreeLighting, the same rule): a
-- revoked lighting is not lit again from the passes it rested on. QB_TREE_EVALUATE reads everything again under the
-- person's lighting lock and lights only what holds; the node's condition may ask for more than the pass.
SELECT DISTINCT a.{{QbAttempt.userId}} AS userId, c.{{QbPublicationCard.treeId}} AS treeId,
       c.{{QbPublicationCard.treeNodeId}} AS nodeId
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
