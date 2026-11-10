/*---
id: qb.sponsor.tree-versions
description: "F-S3: the versions of one of the caller's own knowledge graphs, newest first: the editor's version history (status, when submitted and decided, the reason a version was sent back)"
entities: [QbTree, QbTreeVersion]
datasets:
  QbTree: urn:jabiz:dataset:sponsor:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
params:
  treeId: { like: QbTree.treeId, required: true, description: The graph (the caller's own; none for another's) }
results:
  versionId:    { from: QbTreeVersion.versionId }
  versionNo:    { from: QbTreeVersion.versionNo }
  status:       { from: QbTreeVersion.status }
  name:         { kind: { type: text } }
  submittedAt:  { from: QbTreeVersion.submittedAt }
  decidedAt:    { from: QbTreeVersion.decidedAt }
  rejectReason: { from: QbTreeVersion.rejectReason }
  editedAt:     { from: QbTreeVersion.editedAt }
list:
  sorts: [versionNo]
  defaultSort: { field: versionNo, asc: false }
  key: [versionId]
permissions: [qb.sponsor.me]
---*/
-- The view holds the caller's own graphs only: another's graph has no rows.
SELECT CAST(v.{{QbTreeVersion.versionId}} AS text) AS versionId, v.{{QbTreeVersion.versionNo}} AS versionNo,
       v.{{QbTreeVersion.status}} AS status, CAST(v.{{QbTreeVersion.name}} AS text) AS name,
       v.{{QbTreeVersion.submittedAt}} AS submittedAt, v.{{QbTreeVersion.decidedAt}} AS decidedAt,
       v.{{QbTreeVersion.rejectReason}} AS rejectReason, v.{{QbTreeVersion.editedAt}} AS editedAt
FROM {{QbTree}} t
JOIN {{QbTreeVersion}} v ON v.{{QbTreeVersion.treeId}} = CAST(t.{{QbTree.treeId}} AS text)
WHERE CAST(t.{{QbTree.treeId}} AS text) = CAST(:treeId AS text)
