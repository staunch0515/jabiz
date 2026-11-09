/*---
id: qb.admin.blocks
description: >-
  The figures of blocking (F-S8), for headquarters: by kind (publication, quiz, Sponsor, video provider), how many
  blocks there are and how many takers made them, and how many takers block anything. Aggregates only: never whose
entities: [QbBlock]
datasets:
  QbBlock: urn:jabiz:dataset:default:QbBlock
results:
  kind:        { from: QbBlock.kind }
  blocks:      { kind: { type: numeric, precision: 12, scale: 0 } }
  blockers:    { kind: { type: numeric, precision: 12, scale: 0 } }
  allBlockers: { kind: { type: numeric, precision: 12, scale: 0 } }
list:
  key: [kind]
permissions: [qb.admin.block.read]
---*/
-- One row a kind (the page puts them in the kinds' order), a kind without blocks with zeros; the takers blocking
-- anything on every row.
SELECT
    k.kind                                                        AS kind,
    CAST(count(b.{{QbBlock.blockId}}) AS numeric(12, 0))          AS blocks,
    CAST(count(DISTINCT b.{{QbBlock.userId}}) AS numeric(12, 0))  AS blockers,
    CAST((SELECT count(DISTINCT a.{{QbBlock.userId}}) FROM {{QbBlock}} a) AS numeric(12, 0)) AS allBlockers
FROM (VALUES (1, CAST('PUBLICATION' AS varchar(16))), (2, 'QUIZ'), (3, 'SPONSOR'), (4, 'PROVIDER')) AS k(no, kind)
LEFT JOIN {{QbBlock}} b ON b.{{QbBlock.kind}} = k.kind
GROUP BY k.no, k.kind
