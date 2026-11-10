/*---
id: qb.sponsor.challenges
description: >-
  F-S4c: the Sponsor's challenges, node by node: every node of the copies in effect of the caller's operator (maps
  Sponsors light, F-S4a) with the facts its state needs as the caller sees it (lit, the prerequisites not lit, which of
  the nodes its condition names are lit), what lighting it opens (the reward methods' gate parameters naming its
  template's node, and the platform fee Kudos its template's node grants now) and the challenges bound to it the caller
  can start or has answered. One rule with QB_SPONSOR_ATTEMPT_START (startable: the caller is an approved Sponsor of
  the operator, the card a live challenge of it in its time, and no finished attempt of the caller's).
entities: [SysParam, QbSponsor, QbSponsorOperator, QbTree, QbTreeVersion, QbTreeNode, QbTreeNodeDef, QbTreeNodeRef, QbTreeLit, QbTreeLitRevocation, QbPublicationCard, QbAttempt]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
  QbSponsor: urn:jabiz:dataset:sponsor:QbSponsor
  QbSponsorOperator: urn:jabiz:dataset:sponsor:QbSponsorOperator
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeNodeDef: urn:jabiz:dataset:default:QbTreeNodeDef
  QbTreeNodeRef: urn:jabiz:dataset:default:QbTreeNodeRef
  QbTreeLit: urn:jabiz:dataset:me-sponsor:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:me-sponsor:QbTreeLitRevocation
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the list is for (the page's now) }
results:
  approved:       { kind: { type: bool } }
  treeId:         { from: QbTree.treeId }
  templateTreeId: { from: QbTree.templateTreeId }
  treeName:       { kind: { type: text } }
  nodeId:         { from: QbTreeNodeDef.nodeId }
  name:           { kind: { type: text } }
  description:    { kind: { type: text } }
  condition:      { kind: { type: text } }
  seq:            { from: QbTreeNodeDef.seq }
  lit:            { kind: { type: bool } }
  litAt:          { from: QbTreeLit.litAt }
  unmet:          { kind: { type: text } }
  refs:           { kind: { type: text } }
  gates:          { kind: { type: text, maxLength: 400 } }
  grantAmount:    { from: QbTreeNode.grantAmount }
  publications:   { kind: { type: text } }
list:
  sorts: [treeName, seq]
  defaultSort: { field: seq, asc: true }
  key: [treeId, nodeId]
permissions: [qb.sponsor.challenge]
---*/
-- The caller's operator is their own assignment (the dataset holds the caller's only); none, no rows. What is lit is
-- tree.TreeLights for the caller as a Sponsor (the me-sponsor views: their own records with a SPONSOR subject), named
-- by graph and node together, as qb.app.tree. gates and grantAmount are the template's node's (a copy keeps its
-- template's node ids, LightGrants): the same node lit in another operator's copy opens the same and is granted once.
-- publications are the live challenges bound to the node (cards of the caller's operator, forSponsors, in their time),
-- each {publicationId, name, endAt, attemptId, status, deadline, startable}; a challenge the caller finished is listed
-- (its result) but not startable.
WITH op AS (
    SELECT a.{{QbSponsorOperator.operatorId}} AS operatorId
    FROM {{QbSponsorOperator}} a
),
me AS (
    SELECT bool_or(s.{{QbSponsor.reviewStatus}} = 'APPROVED') AS approved FROM {{QbSponsor}} s
),
g AS (
    SELECT t.{{QbTree.treeId}} AS treeId, t.{{QbTree.templateTreeId}} AS templateTreeId,
           t.{{QbTree.currentVersionId}} AS versionId
    FROM {{QbTree}} t
    JOIN op ON t.{{QbTree.ownerId}} = CAST(op.operatorId AS text)
    WHERE t.{{QbTree.ownerKind}} = 'OPERATOR' AND t.{{QbTree.subjectKind}} = 'SPONSOR'
      AND t.{{QbTree.status}} = 'ACTIVE' AND t.{{QbTree.currentVersionId}} IS NOT NULL
),
lit AS (
    SELECT l.{{QbTreeLit.treeId}} AS treeId, l.{{QbTreeLit.nodeId}} AS nodeId, min(l.{{QbTreeLit.litAt}}) AS litAt
    FROM {{QbTreeLit}} l
    WHERE NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                      WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))
    GROUP BY l.{{QbTreeLit.treeId}}, l.{{QbTreeLit.nodeId}}
),
gate AS (
    SELECT CAST(p.{{SysParam.value}} AS text) AS target,
           string_agg(p.{{SysParam.paramKey}}, ',' ORDER BY p.{{SysParam.paramKey}}) AS gates
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} LIKE 'qb.tree.gate.%'
    GROUP BY CAST(p.{{SysParam.value}} AS text)
)
SELECT coalesce((SELECT approved FROM me), false) AS approved,
       CAST(g.treeId AS text) AS treeId,
       CAST(g.templateTreeId AS text) AS templateTreeId,
       CAST(v.{{QbTreeVersion.name}} AS text) AS treeName,
       d.{{QbTreeNodeDef.nodeId}} AS nodeId,
       CAST(d.{{QbTreeNodeDef.name}} AS text) AS name,
       CAST(d.{{QbTreeNodeDef.description}} AS text) AS description,
       d.{{QbTreeNodeDef.condition}} AS condition,
       d.{{QbTreeNodeDef.seq}} AS seq,
       (mine.nodeId IS NOT NULL) AS lit,
       mine.litAt AS litAt,
       CAST((SELECT coalesce(jsonb_agg(ba.node ORDER BY ba.ord), '[]'::jsonb)
             FROM jsonb_array_elements_text(CAST(coalesce(n.{{QbTreeNode.beforeAnswer}}, '[]') AS jsonb))
                  WITH ORDINALITY AS ba(node, ord)
             WHERE NOT EXISTS (SELECT 1 FROM lit
                               WHERE lit.treeId = split_part(ba.node, '/', 1)
                                 AND lit.nodeId = split_part(ba.node, '/', 2))) AS text) AS unmet,
       CAST((SELECT coalesce(jsonb_agg(jsonb_build_object(
                     'node', r.{{QbTreeNodeRef.refNodeId}}, 'tree', r.{{QbTreeNodeRef.refTreeId}},
                     'name', (SELECT CAST(rd.{{QbTreeNodeDef.name}} AS jsonb)
                              FROM {{QbTree}} rt
                              JOIN {{QbTreeNodeDef}} rd ON rd.{{QbTreeNodeDef.versionId}} = rt.{{QbTree.currentVersionId}}
                              WHERE CAST(rt.{{QbTree.treeId}} AS text) = r.{{QbTreeNodeRef.refTreeId}}
                                AND rd.{{QbTreeNodeDef.nodeId}} = r.{{QbTreeNodeRef.refNodeId}}),
                     'lit', EXISTS (SELECT 1 FROM lit
                                    WHERE lit.treeId = r.{{QbTreeNodeRef.refTreeId}}
                                      AND lit.nodeId = r.{{QbTreeNodeRef.refNodeId}}))
                     ORDER BY r.{{QbTreeNodeRef.refNodeId}}), '[]'::jsonb)
             FROM {{QbTreeNodeRef}} r
             WHERE r.{{QbTreeNodeRef.treeId}} = CAST(g.treeId AS text)
               AND r.{{QbTreeNodeRef.nodeId}} = d.{{QbTreeNodeDef.nodeId}}) AS text) AS refs,
       gate.gates AS gates,
       tn.{{QbTreeNode.grantAmount}} AS grantAmount,
       CAST((SELECT coalesce(jsonb_agg(jsonb_build_object(
                     'publicationId', CAST(c.{{QbPublicationCard.publicationId}} AS text),
                     'name', c.{{QbPublicationCard.name}},
                     'translations', CAST(c.{{QbPublicationCard.translations}} AS jsonb),
                     'endAt', c.{{QbPublicationCard.endAt}},
                     'attemptId', CAST(m.{{QbAttempt.attemptId}} AS text),
                     'status', m.{{QbAttempt.status}},
                     'deadline', m.{{QbAttempt.deadline}},
                     'startable', coalesce((SELECT approved FROM me), false)
                         AND (m.{{QbAttempt.attemptId}} IS NULL
                              OR (m.{{QbAttempt.status}} = 'IN_PROGRESS' AND m.{{QbAttempt.deadline}} > :at)))
                     ORDER BY c.{{QbPublicationCard.endAt}}, c.{{QbPublicationCard.publicationId}}), '[]'::jsonb)
             FROM {{QbPublicationCard}} c
             JOIN op ON CAST(c.{{QbPublicationCard.operatorId}} AS text) = CAST(op.operatorId AS text)
             LEFT JOIN {{QbAttempt}} m ON m.{{QbAttempt.publicationId}} = c.{{QbPublicationCard.publicationId}}
             WHERE c.{{QbPublicationCard.forSponsors}}
               AND c.{{QbPublicationCard.status}} = 'LIVE'
               AND c.{{QbPublicationCard.startAt}} <= :at AND :at < c.{{QbPublicationCard.endAt}}
               AND CAST(c.{{QbPublicationCard.treeId}} AS text) = CAST(g.treeId AS text)
               AND c.{{QbPublicationCard.treeNodeId}} = d.{{QbTreeNodeDef.nodeId}}) AS text) AS publications
FROM g
JOIN {{QbTreeVersion}} v ON v.{{QbTreeVersion.versionId}} = g.versionId
JOIN {{QbTreeNodeDef}} d ON d.{{QbTreeNodeDef.versionId}} = g.versionId
JOIN {{QbTreeNode}} n ON n.{{QbTreeNode.treeId}} = CAST(g.treeId AS text)
    AND n.{{QbTreeNode.nodeId}} = d.{{QbTreeNodeDef.nodeId}} AND n.{{QbTreeNode.active}}
LEFT JOIN {{QbTreeNode}} tn ON tn.{{QbTreeNode.treeId}} = CAST(g.templateTreeId AS text)
    AND tn.{{QbTreeNode.nodeId}} = d.{{QbTreeNodeDef.nodeId}} AND tn.{{QbTreeNode.active}}
LEFT JOIN lit mine ON mine.treeId = CAST(g.treeId AS text) AND mine.nodeId = d.{{QbTreeNodeDef.nodeId}}
LEFT JOIN gate ON gate.target = CAST(g.templateTreeId AS text) || '/' || d.{{QbTreeNodeDef.nodeId}}
