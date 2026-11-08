/*---
id: qb.system.tree-graphs
description: >-
  The node definitions of the knowledge graphs in effect a draft may name nodes of (F-S2a), for the rules' checks:
  a Sponsor's draft, the platform's templates lit by Takers; a template, the platform's other templates; an
  operator's copy, the operator's other copies. One row a node of a graph's version in effect, never the draft's own
  graph (its draft replaces it).
entities: [QbTree, QbTreeVersion, QbTreeNodeDef]
datasets:
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNodeDef: urn:jabiz:dataset:default:QbTreeNodeDef
params:
  treeId:      { kind: { type: text, maxLength: 36 }, required: true }
  ownerKind:   { kind: { type: text, maxLength: 10 }, required: true }
  ownerId:     { kind: { type: text, maxLength: 128 } }
  subjectKind: { kind: { type: text, maxLength: 10 }, required: true }
results:
  treeId:      { kind: { type: text, maxLength: 36 } }
  ownerKind:   { kind: { type: text, maxLength: 10 } }
  subjectKind: { kind: { type: text, maxLength: 10 } }
  nodeId:      { kind: { type: text, maxLength: 36 } }
  condition:   { kind: { type: text, maxLength: 100000 } }
permissions: [qb.tree.edit]
---*/
-- The rules (tree.TreeRules) decide what a draft may name; this only gives the graphs it may name at all.
SELECT t.{{QbTree.treeId}}      AS treeId,
       t.{{QbTree.ownerKind}}   AS ownerKind,
       t.{{QbTree.subjectKind}} AS subjectKind,
       d.{{QbTreeNodeDef.nodeId}}    AS nodeId,
       d.{{QbTreeNodeDef.condition}} AS condition
FROM {{QbTree}} t
JOIN {{QbTreeVersion}} v ON v.{{QbTreeVersion.versionId}} = t.{{QbTree.currentVersionId}}
JOIN {{QbTreeNodeDef}} d ON d.{{QbTreeNodeDef.versionId}} = v.{{QbTreeVersion.versionId}}
WHERE t.{{QbTree.status}} = 'ACTIVE'
  AND t.{{QbTree.treeId}} <> CAST(:treeId AS text)
  AND (
        (CAST(:ownerKind AS text) = 'SPONSOR' AND t.{{QbTree.ownerKind}} = 'PLATFORM'
            AND t.{{QbTree.subjectKind}} = 'TAKER')
     OR (CAST(:ownerKind AS text) = 'PLATFORM' AND t.{{QbTree.ownerKind}} = 'PLATFORM'
            AND t.{{QbTree.subjectKind}} = CAST(:subjectKind AS text))
     OR (CAST(:ownerKind AS text) = 'OPERATOR' AND t.{{QbTree.ownerKind}} = 'OPERATOR'
            AND t.{{QbTree.ownerId}} = CAST(:ownerId AS text))
  )
