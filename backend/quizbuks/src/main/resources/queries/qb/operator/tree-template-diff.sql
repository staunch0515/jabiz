/*---
id: qb.operator.tree-template-diff
description: "F-S3e: an operator's copy against its template's version in effect, node by node, for taking the template's changes (QB_TREE_ADOPT): a node the template has and the copy does not (ADDED), has otherwise (CHANGED) or the same (SAME), and a node of the copy the template does not have (NOT_IN_TEMPLATE: taking it removes it). The copy is its open version, else the one in effect. Only the caller's operator's copies; none for another map"
entities: [QbTree, QbTreeVersion, QbTreeNodeDef, QbOperatorMember]
datasets:
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNodeDef: urn:jabiz:dataset:default:QbTreeNodeDef
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
params:
  treeId: { like: QbTree.treeId, required: true, description: The operator's copy }
results:
  nodeId:            { from: QbTreeNodeDef.nodeId }
  change:            { kind: { type: text, maxLength: 20 } }
  name:              { kind: { type: text } }
  copyName:          { kind: { type: text } }
  condition:         { kind: { type: text } }
  seq:               { kind: { type: numeric, precision: 4, scale: 0 } }
  templateVersionNo: { kind: { type: numeric, precision: 6, scale: 0 } }
  takenVersionNo:    { kind: { type: numeric, precision: 6, scale: 0 } }
list:
  sorts: [seq]
  defaultSort: { field: seq, asc: true }
  filters: [change]
  key: [nodeId]
permissions: [qb.operator.use]
---*/
-- Limited by the caller's own, current membership (none: no rows), as qb.operator.trees. A node is compared by its
-- name, description and condition as stored (the JSON text). templateVersionNo is the template's version in effect,
-- takenVersionNo the one the copy took last (QbTree.templateVersionNo).
WITH copy AS (
    SELECT t.{{QbTree.treeId}} AS treeId, t.{{QbTree.templateTreeId}} AS templateTreeId,
           t.{{QbTree.templateVersionNo}} AS takenVersionNo,
           coalesce((SELECT o.{{QbTreeVersion.versionId}} FROM {{QbTreeVersion}} o
                     WHERE o.{{QbTreeVersion.treeId}} = CAST(t.{{QbTree.treeId}} AS text)
                       AND o.{{QbTreeVersion.status}} IN ('DRAFT', 'REVIEW', 'REJECTED')
                     LIMIT 1), t.{{QbTree.currentVersionId}}) AS versionId
    FROM {{QbTree}} t
    JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
        AND t.{{QbTree.ownerKind}} = 'OPERATOR' AND t.{{QbTree.ownerId}} = me.{{QbOperatorMember.operatorId}}
    WHERE CAST(t.{{QbTree.treeId}} AS text) = CAST(:treeId AS text)
),
template AS (
    SELECT tt.{{QbTree.currentVersionId}} AS versionId, tv.{{QbTreeVersion.versionNo}} AS versionNo
    FROM copy
    JOIN {{QbTree}} tt ON CAST(tt.{{QbTree.treeId}} AS text) = copy.templateTreeId
    JOIN {{QbTreeVersion}} tv ON tv.{{QbTreeVersion.versionId}} = tt.{{QbTree.currentVersionId}}
),
t AS (
    SELECT d.{{QbTreeNodeDef.nodeId}} AS nodeId, CAST(d.{{QbTreeNodeDef.name}} AS text) AS name,
           CAST(d.{{QbTreeNodeDef.description}} AS text) AS description, d.{{QbTreeNodeDef.condition}} AS condition,
           d.{{QbTreeNodeDef.seq}} AS seq
    FROM {{QbTreeNodeDef}} d JOIN template ON d.{{QbTreeNodeDef.versionId}} = template.versionId
),
c AS (
    SELECT d.{{QbTreeNodeDef.nodeId}} AS nodeId, CAST(d.{{QbTreeNodeDef.name}} AS text) AS name,
           CAST(d.{{QbTreeNodeDef.description}} AS text) AS description, d.{{QbTreeNodeDef.condition}} AS condition,
           d.{{QbTreeNodeDef.seq}} AS seq
    FROM {{QbTreeNodeDef}} d JOIN copy ON d.{{QbTreeNodeDef.versionId}} = copy.versionId
)
SELECT coalesce(t.nodeId, c.nodeId) AS nodeId,
       CASE WHEN c.nodeId IS NULL THEN 'ADDED'
            WHEN t.nodeId IS NULL THEN 'NOT_IN_TEMPLATE'
            WHEN t.name IS DISTINCT FROM c.name OR t.description IS DISTINCT FROM c.description
                 OR t.condition IS DISTINCT FROM c.condition THEN 'CHANGED'
            ELSE 'SAME' END AS change,
       coalesce(t.name, c.name) AS name, c.name AS copyName,
       coalesce(t.condition, c.condition) AS condition,
       coalesce(t.seq, c.seq) AS seq,
       (SELECT versionNo FROM template) AS templateVersionNo,
       (SELECT takenVersionNo FROM copy) AS takenVersionNo
FROM t FULL JOIN c ON c.nodeId = t.nodeId
WHERE EXISTS (SELECT 1 FROM copy)
