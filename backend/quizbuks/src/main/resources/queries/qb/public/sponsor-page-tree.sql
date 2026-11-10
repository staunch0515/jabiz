/*---
id: qb.public.sponsor-page-tree
description: >-
  F-S3c: the knowledge graph in effect of the Sponsor whose published page has the handle, node by node (what the
  page shows to anybody): the names, descriptions and conditions only, nothing of anybody's lighting; none when the
  page is not published or the Sponsor has no graph in effect
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [QbSponsorPage, QbSponsor, QbTree, QbTreeVersion, QbTreeNode, QbTreeNodeDef, QbTreeNodeRef]
datasets:
  QbSponsorPage: urn:jabiz:dataset:public:QbSponsorPage
  QbSponsor: urn:jabiz:dataset:public:QbSponsor
  QbTree: urn:jabiz:dataset:public:QbTree
  QbTreeVersion: urn:jabiz:dataset:public:QbTreeVersion
  QbTreeNode: urn:jabiz:dataset:public:QbTreeNode
  QbTreeNodeDef: urn:jabiz:dataset:public:QbTreeNodeDef
  QbTreeNodeRef: urn:jabiz:dataset:public:QbTreeNodeRef
params:
  handle: { like: QbSponsorPage.handle, required: true, description: The page's handle (lowercase) }
results:
  treeId:      { from: QbTree.treeId }
  ownerKind:   { from: QbTree.ownerKind }
  treeName:    { kind: { type: text } }
  treeDescription: { kind: { type: text } }
  nodeId:      { from: QbTreeNodeDef.nodeId }
  name:        { kind: { type: text } }
  description: { kind: { type: text } }
  condition:   { kind: { type: text } }
  seq:         { from: QbTreeNodeDef.seq }
  refs:        { kind: { type: text } }
list:
  sorts: [seq]
  defaultSort: { field: seq, asc: true }
  key: [treeId, nodeId]
---*/
-- The public views hold the graphs Takers light that are in effect and their versions in effect only (fixed scope);
-- the page shows only while its Sponsor is approved, as qb.public.sponsor-page does. refs are the nodes the condition
-- names as the version in effect resolved them, each {node, tree, name, lit}, never lit: nobody is signed in (the
-- signed-in page reads qb.app.tree instead).
SELECT CAST(t.{{QbTree.treeId}} AS text) AS treeId, t.{{QbTree.ownerKind}} AS ownerKind,
       CAST(v.{{QbTreeVersion.name}} AS text) AS treeName,
       CAST(v.{{QbTreeVersion.description}} AS text) AS treeDescription,
       d.{{QbTreeNodeDef.nodeId}} AS nodeId,
       CAST(d.{{QbTreeNodeDef.name}} AS text) AS name,
       CAST(d.{{QbTreeNodeDef.description}} AS text) AS description,
       d.{{QbTreeNodeDef.condition}} AS condition,
       d.{{QbTreeNodeDef.seq}} AS seq,
       CAST((SELECT coalesce(jsonb_agg(jsonb_build_object(
                     'node', r.{{QbTreeNodeRef.refNodeId}}, 'tree', r.{{QbTreeNodeRef.refTreeId}},
                     'name', (SELECT CAST(rd.{{QbTreeNodeDef.name}} AS jsonb)
                              FROM {{QbTree}} rt
                              JOIN {{QbTreeNodeDef}} rd ON rd.{{QbTreeNodeDef.versionId}} = rt.{{QbTree.currentVersionId}}
                              WHERE CAST(rt.{{QbTree.treeId}} AS text) = r.{{QbTreeNodeRef.refTreeId}}
                                AND rd.{{QbTreeNodeDef.nodeId}} = r.{{QbTreeNodeRef.refNodeId}}),
                     'lit', false)
                     ORDER BY r.{{QbTreeNodeRef.refNodeId}}), '[]'::jsonb)
             FROM {{QbTreeNodeRef}} r
             WHERE r.{{QbTreeNodeRef.treeId}} = CAST(t.{{QbTree.treeId}} AS text)
               AND r.{{QbTreeNodeRef.nodeId}} = d.{{QbTreeNodeDef.nodeId}}) AS text) AS refs
FROM {{QbSponsorPage}} p
JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = p.{{QbSponsorPage.userId}}
JOIN {{QbTree}} t ON t.{{QbTree.ownerKind}} = 'SPONSOR' AND t.{{QbTree.ownerId}} = p.{{QbSponsorPage.userId}}
JOIN {{QbTreeVersion}} v ON v.{{QbTreeVersion.versionId}} = t.{{QbTree.currentVersionId}}
JOIN {{QbTreeNodeDef}} d ON d.{{QbTreeNodeDef.versionId}} = v.{{QbTreeVersion.versionId}}
JOIN {{QbTreeNode}} n ON n.{{QbTreeNode.treeId}} = CAST(t.{{QbTree.treeId}} AS text)
    AND n.{{QbTreeNode.nodeId}} = d.{{QbTreeNodeDef.nodeId}} AND n.{{QbTreeNode.active}}
WHERE p.{{QbSponsorPage.handle}} = :handle
