/*---
id: qb.sponsor.package-trees
description: >-
  F-C4: the caller's own knowledge maps not retired, each with the version a sponsor package exports (the one in effect,
  else the newest draft) and whether it was ever submitted
entities: [QbTree, QbTreeVersion]
datasets:
  QbTree: urn:jabiz:dataset:sponsor:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
results:
  treeId:           { from: QbTree.treeId }
  currentVersionId: { from: QbTree.currentVersionId }
  versionId:        { kind: { type: text, maxLength: 36 } }
  name:             { kind: { type: text } }
  description:      { kind: { type: text } }
  submitted:        { kind: { type: bool } }
list:
  key: [treeId]
  sorts: [treeId]
  defaultSort: { field: treeId, asc: true }
permissions: [qb.sponsor.me]
---*/
-- The caller's maps only (the dataset is limited to the caller); the versions through them.
SELECT t.{{QbTree.treeId}} AS treeId, t.{{QbTree.currentVersionId}} AS currentVersionId,
       x.{{QbTreeVersion.versionId}} AS versionId,
       CAST(x.{{QbTreeVersion.name}} AS text) AS name,
       CAST(x.{{QbTreeVersion.description}} AS text) AS description,
       (t.{{QbTree.currentVersionId}} IS NOT NULL OR EXISTS (
            SELECT 1 FROM {{QbTreeVersion}} s
            WHERE s.{{QbTreeVersion.treeId}} = t.{{QbTree.treeId}} AND s.{{QbTreeVersion.submittedAt}} IS NOT NULL))
           AS submitted
FROM {{QbTree}} t
JOIN LATERAL (
    SELECT v.{{QbTreeVersion.versionId}}, v.{{QbTreeVersion.name}}, v.{{QbTreeVersion.description}}
    FROM {{QbTreeVersion}} v
    WHERE v.{{QbTreeVersion.treeId}} = t.{{QbTree.treeId}}
      AND (v.{{QbTreeVersion.versionId}} = t.{{QbTree.currentVersionId}}
           OR (t.{{QbTree.currentVersionId}} IS NULL AND v.{{QbTreeVersion.status}} IN ('DRAFT', 'REVIEW', 'REJECTED')))
    ORDER BY v.{{QbTreeVersion.versionNo}} DESC
    LIMIT 1
) x ON TRUE
WHERE t.{{QbTree.status}} = 'ACTIVE'
