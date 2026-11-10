/*---
id: qb.sponsor.trees
description: "F-S3: the caller's own knowledge graphs (a Sponsor's), each with its version in effect and its open version (a draft, one under review or one sent back, with the reason), for the graph page and the editor"
entities: [QbTree, QbTreeVersion, QbTreeNode, QbPublicationCard]
datasets:
  QbTree: urn:jabiz:dataset:sponsor:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
results:
  treeId:           { from: QbTree.treeId }
  status:           { from: QbTree.status }
  revision:         { from: QbTree.revision }
  createdAt:        { from: QbTree.createdAt }
  currentVersionId: { from: QbTree.currentVersionId }
  currentVersionNo: { kind: { type: numeric, precision: 6, scale: 0 } }
  name:             { kind: { type: text } }
  description:      { kind: { type: text } }
  openVersionId:    { kind: { type: text, maxLength: 36 } }
  openVersionNo:    { kind: { type: numeric, precision: 6, scale: 0 } }
  openStatus:       { kind: { type: text, maxLength: 20 } }
  rejectReason:     { kind: { type: text } }
  submittedAt:      { from: QbTreeVersion.submittedAt }
  editedAt:         { from: QbTreeVersion.editedAt }
  nodes:            { kind: { type: numeric, precision: 9, scale: 0 } }
  runningBound:     { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [createdAt]
  defaultSort: { field: createdAt, asc: false }
  key: [treeId]
permissions: [qb.sponsor.me]
---*/
-- The view holds the caller's own graphs only (ownerKind SPONSOR, ownerId the caller). The open version is the newest
-- one that is not in effect and not superseded: a draft, one under review or one sent back (REJECTED is the draft to
-- change, with the reviewer's reason). The name shown is the open version's, else the one in effect. runningBound is
-- the publications bound to the graph's nodes that are not ended (the editor warns before removing their nodes).
SELECT CAST(t.{{QbTree.treeId}} AS text) AS treeId, t.{{QbTree.status}} AS status, t.{{QbTree.revision}} AS revision,
       t.{{QbTree.createdAt}} AS createdAt, t.{{QbTree.currentVersionId}} AS currentVersionId,
       cv.{{QbTreeVersion.versionNo}} AS currentVersionNo,
       CAST(coalesce(ov.{{QbTreeVersion.name}}, cv.{{QbTreeVersion.name}}) AS text) AS name,
       CAST(coalesce(ov.{{QbTreeVersion.description}}, cv.{{QbTreeVersion.description}}) AS text) AS description,
       CAST(ov.{{QbTreeVersion.versionId}} AS text) AS openVersionId, ov.{{QbTreeVersion.versionNo}} AS openVersionNo,
       ov.{{QbTreeVersion.status}} AS openStatus, ov.{{QbTreeVersion.rejectReason}} AS rejectReason,
       ov.{{QbTreeVersion.submittedAt}} AS submittedAt, ov.{{QbTreeVersion.editedAt}} AS editedAt,
       (SELECT count(*) FROM {{QbTreeNode}} n
        WHERE n.{{QbTreeNode.treeId}} = CAST(t.{{QbTree.treeId}} AS text) AND n.{{QbTreeNode.active}}) AS nodes,
       (SELECT count(*) FROM {{QbPublicationCard}} c
        WHERE c.{{QbPublicationCard.treeId}} = CAST(t.{{QbTree.treeId}} AS text)
          AND c.{{QbPublicationCard.status}} IN ('SCHEDULED', 'LIVE', 'PAUSED', 'FUNDS_SHORT')) AS runningBound
FROM {{QbTree}} t
LEFT JOIN {{QbTreeVersion}} cv ON cv.{{QbTreeVersion.versionId}} = t.{{QbTree.currentVersionId}}
LEFT JOIN LATERAL (
    SELECT v.* FROM {{QbTreeVersion}} v
    WHERE v.{{QbTreeVersion.treeId}} = CAST(t.{{QbTree.treeId}} AS text)
      AND v.{{QbTreeVersion.status}} IN ('DRAFT', 'REVIEW', 'REJECTED')
    ORDER BY v.{{QbTreeVersion.versionNo}} DESC
    LIMIT 1) ov ON TRUE
