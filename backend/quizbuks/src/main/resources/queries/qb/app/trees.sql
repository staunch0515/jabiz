/*---
id: qb.app.trees
description: "F-S3: the knowledge graphs in effect the caller's \"my graphs\" lists: the platform's graphs Takers light, and each Sponsor's graph the caller has lit a node of or answered a publication bound to, with how many of its nodes the caller has lit"
entities: [QbTree, QbTreeVersion, QbTreeNode, QbTreeLit, QbTreeLitRevocation, QbAttempt, QbPublicationCard, QbSponsor, QbSponsorPage]
datasets:
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeLit: urn:jabiz:dataset:me:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:me:QbTreeLitRevocation
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorPage: urn:jabiz:dataset:public:QbSponsorPage
params:
  handle: { like: QbSponsorPage.handle, description: "F-S3c: instead, the graphs in effect of the Sponsor whose published page has this handle (the Sponsor's page shows them to everyone signed in)" }
results:
  treeId:      { from: QbTree.treeId }
  ownerKind:   { from: QbTree.ownerKind }
  ownerId:     { from: QbTree.ownerId }
  sponsorCompany: { from: QbSponsor.company }
  name:        { kind: { type: text } }
  description: { kind: { type: text } }
  nodes:       { kind: { type: numeric, precision: 9, scale: 0 } }
  litNodes:    { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [ownerKind]
  defaultSort: { field: ownerKind, asc: true }
  key: [treeId]
permissions: [qb.play]
---*/
-- Graphs in effect that Takers light: the platform's (every Taker sees them) and a Sponsor's that the caller has a lit
-- record in or an attempt of a publication bound to (the views hold the caller's own records only). Lit: a lit record
-- with no revocation, by graph and node (tree.TreeLights); only the nodes in effect count. The Sponsor's name is an
-- approved onboarding's only, as the feed's.
WITH mine AS (
    SELECT l.{{QbTreeLit.treeId}} AS treeId FROM {{QbTreeLit}} l
    UNION
    SELECT c.{{QbPublicationCard.treeId}} FROM {{QbAttempt}} a
    JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = a.{{QbAttempt.publicationId}}
    WHERE c.{{QbPublicationCard.treeId}} IS NOT NULL
),
lit AS (
    SELECT DISTINCT l.{{QbTreeLit.treeId}} AS treeId, l.{{QbTreeLit.nodeId}} AS nodeId
    FROM {{QbTreeLit}} l
    WHERE NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                      WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))
)
SELECT CAST(t.{{QbTree.treeId}} AS text) AS treeId, t.{{QbTree.ownerKind}} AS ownerKind, t.{{QbTree.ownerId}} AS ownerId,
       (SELECT s.{{QbSponsor.company}} FROM {{QbSponsor}} s
        WHERE s.{{QbSponsor.userId}} = t.{{QbTree.ownerId}} AND s.{{QbSponsor.reviewStatus}} = 'APPROVED') AS sponsorCompany,
       CAST(v.{{QbTreeVersion.name}} AS text) AS name, CAST(v.{{QbTreeVersion.description}} AS text) AS description,
       (SELECT count(*) FROM {{QbTreeNode}} n
        WHERE n.{{QbTreeNode.treeId}} = CAST(t.{{QbTree.treeId}} AS text) AND n.{{QbTreeNode.active}}) AS nodes,
       (SELECT count(*) FROM {{QbTreeNode}} n
        JOIN lit ON lit.treeId = n.{{QbTreeNode.treeId}} AND lit.nodeId = n.{{QbTreeNode.nodeId}}
        WHERE n.{{QbTreeNode.treeId}} = CAST(t.{{QbTree.treeId}} AS text) AND n.{{QbTreeNode.active}}) AS litNodes
FROM {{QbTree}} t
JOIN {{QbTreeVersion}} v ON v.{{QbTreeVersion.versionId}} = t.{{QbTree.currentVersionId}}
WHERE t.{{QbTree.status}} = 'ACTIVE' AND t.{{QbTree.subjectKind}} = 'TAKER'
  AND (CASE WHEN CAST(:handle AS text) IS NOT NULL
            -- F-S3c: the Sponsor's page asks for that Sponsor's graphs (a graph Takers light is everybody's to see).
            THEN t.{{QbTree.ownerKind}} = 'SPONSOR' AND t.{{QbTree.ownerId}} IN (
                     SELECT pg.{{QbSponsorPage.userId}} FROM {{QbSponsorPage}} pg
                     WHERE pg.{{QbSponsorPage.handle}} = CAST(:handle AS text))
            ELSE t.{{QbTree.ownerKind}} = 'PLATFORM'
                 OR (t.{{QbTree.ownerKind}} = 'SPONSOR'
                     AND EXISTS (SELECT 1 FROM mine WHERE mine.treeId = CAST(t.{{QbTree.treeId}} AS text))) END)
