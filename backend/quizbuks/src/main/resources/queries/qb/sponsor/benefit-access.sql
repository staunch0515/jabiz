/*---
id: qb.sponsor.benefit-access
description: >-
  F-S2c: whether each of the two benefit roles is open to the caller, and the knowledge map's node that opens it (the
  controlled parameters qb.tree.gate.benefit-provider-node and qb.tree.gate.benefit-sponsor-node; none: closed), for
  the pages that need one
entities: [SysParam, QbTree, QbTreeNode, QbTreeNodeDef, QbTreeLit, QbTreeLitRevocation]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeNodeDef: urn:jabiz:dataset:default:QbTreeNodeDef
  QbTreeLit: urn:jabiz:dataset:me:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:me:QbTreeLitRevocation
results:
  certification: { kind: { type: text, maxLength: 40 } }
  open:          { kind: { type: bool } }
  treeId:        { kind: { type: text, maxLength: 36 } }
  nodeId:        { kind: { type: text, maxLength: 36 } }
  nodeName:      { from: QbTreeNodeDef.name }
list:
  key: [certification]
  sorts: [certification]
  defaultSort: { field: certification, asc: true }
permissions: [qb.benefit.use]
---*/
-- One row per benefit role. The rule is tree.TreeGates's (the processes decide with it; this only tells the pages
-- what to show): the parameter names <treeId>/<nodeId> of a node in effect of a platform map Takers light, and the
-- caller has a lit record of that node without a revocation. A malformed parameter names nothing (closed).
WITH gate(role, param_key) AS (
    VALUES ('BENEFIT_PROVIDER', 'qb.tree.gate.benefit-provider-node'),
           ('BENEFIT_SPONSOR', 'qb.tree.gate.benefit-sponsor-node')
), named AS (
    SELECT g.role, split_part(p.{{SysParam.value}}, '/', 1) AS tree_id, split_part(p.{{SysParam.value}}, '/', 2) AS node_id
    FROM gate g
    LEFT JOIN {{SysParam}} p
        ON p.{{SysParam.paramKey}} = g.param_key
       AND p.{{SysParam.value}} ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
)
SELECT
    n.role AS certification,
    (t.{{QbTree.treeId}} IS NOT NULL AND tn.{{QbTreeNode.nodeId}} IS NOT NULL AND EXISTS (
        SELECT 1 FROM {{QbTreeLit}} tl
        WHERE tl.{{QbTreeLit.subjectKind}} = 'USER'
          AND tl.{{QbTreeLit.treeId}} = n.tree_id AND tl.{{QbTreeLit.nodeId}} = n.node_id
          AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} tr
                          WHERE tr.{{QbTreeLitRevocation.litId}} = CAST(tl.{{QbTreeLit.litId}} AS text)))) AS open,
    CAST(CASE WHEN tn.{{QbTreeNode.nodeId}} IS NOT NULL THEN n.tree_id END AS varchar(36)) AS treeId,
    CAST(CASE WHEN tn.{{QbTreeNode.nodeId}} IS NOT NULL THEN n.node_id END AS varchar(36)) AS nodeId,
    nd.{{QbTreeNodeDef.name}} AS nodeName
FROM named n
LEFT JOIN {{QbTree}} t
    ON CAST(t.{{QbTree.treeId}} AS text) = n.tree_id
   AND t.{{QbTree.ownerKind}} = 'PLATFORM' AND t.{{QbTree.subjectKind}} = 'TAKER' AND t.{{QbTree.status}} = 'ACTIVE'
LEFT JOIN {{QbTreeNode}} tn
    ON t.{{QbTree.treeId}} IS NOT NULL
   AND tn.{{QbTreeNode.treeId}} = n.tree_id AND tn.{{QbTreeNode.nodeId}} = n.node_id AND tn.{{QbTreeNode.active}}
LEFT JOIN {{QbTreeNodeDef}} nd
    ON tn.{{QbTreeNode.nodeId}} IS NOT NULL
   AND nd.{{QbTreeNodeDef.versionId}} = t.{{QbTree.currentVersionId}} AND nd.{{QbTreeNodeDef.nodeId}} = n.node_id
