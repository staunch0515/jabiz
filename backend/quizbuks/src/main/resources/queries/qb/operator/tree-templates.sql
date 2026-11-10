/*---
id: qb.operator.tree-templates
description: "F-S3e: the platform's templates an operator's member may copy (QB_TREE_COPY): in effect and lit by Sponsors, each with its version in effect and the caller's operator's copy of it, if any (one copy per template)"
entities: [QbTree, QbTreeVersion, QbOperatorMember]
datasets:
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
results:
  treeId:     { from: QbTree.treeId }
  name:       { kind: { type: text } }
  versionNo:  { kind: { type: numeric, precision: 6, scale: 0 } }
  copyTreeId: { kind: { type: text, maxLength: 36 } }
list:
  sorts: [treeId]
  defaultSort: { field: treeId, asc: true }
  key: [treeId]
permissions: [qb.operator.use]
---*/
-- Only for a current member (none: no rows); the copy is the caller's operator's, whatever its state.
SELECT t.{{QbTree.treeId}} AS treeId, CAST(v.{{QbTreeVersion.name}} AS text) AS name,
       v.{{QbTreeVersion.versionNo}} AS versionNo,
       (SELECT CAST(c.{{QbTree.treeId}} AS text) FROM {{QbTree}} c
        WHERE c.{{QbTree.ownerKind}} = 'OPERATOR' AND c.{{QbTree.ownerId}} = me.{{QbOperatorMember.operatorId}}
          AND c.{{QbTree.templateTreeId}} = CAST(t.{{QbTree.treeId}} AS text)
        LIMIT 1) AS copyTreeId
FROM {{QbTree}} t
JOIN {{QbTreeVersion}} v ON v.{{QbTreeVersion.versionId}} = t.{{QbTree.currentVersionId}}
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
WHERE t.{{QbTree.ownerKind}} = 'PLATFORM' AND t.{{QbTree.subjectKind}} = 'SPONSOR' AND t.{{QbTree.status}} = 'ACTIVE'
