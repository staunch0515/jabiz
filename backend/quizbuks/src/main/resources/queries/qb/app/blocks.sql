/*---
id: qb.app.blocks
description: >-
  The taker's blocks (F-S8), newest first: what each blocks (a publication, a quiz, a Sponsor or a video provider) and
  its name, for the list in the app's settings where each is unblocked
entities: [QbBlock, QbPublicationCard, QbSponsor]
datasets:
  QbBlock: urn:jabiz:dataset:me:QbBlock
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
results:
  blockId:       { from: QbBlock.blockId }
  kind:          { from: QbBlock.kind }
  objectId:      { from: QbBlock.objectId }
  name:          { kind: { type: text, maxLength: 200 } }
  publicationId: { from: QbPublicationCard.publicationId }
  createdAt:     { from: QbBlock.createdAt }
list:
  filters: [kind]
  sorts:   [createdAt]
  defaultSort: { field: createdAt, asc: false }
  key: [blockId]
permissions: [qb.play]
---*/
-- The caller's own rows only (the view). The name: a publication's from its card, a quiz's title from its latest card
-- (the cards froze it), a Sponsor's or a video provider's company. A publication's object is its id as text: cast back
-- for that kind only (a CASE: the other kinds' objects are not publications' ids).
SELECT
    b.{{QbBlock.blockId}}   AS blockId,
    b.{{QbBlock.kind}}      AS kind,
    b.{{QbBlock.objectId}}  AS objectId,
    CAST(CASE b.{{QbBlock.kind}}
        WHEN 'PUBLICATION' THEN pc.{{QbPublicationCard.name}}
        WHEN 'QUIZ' THEN (SELECT qc.{{QbPublicationCard.quizTitle}} FROM {{QbPublicationCard}} qc
                          WHERE qc.{{QbPublicationCard.quizId}} = b.{{QbBlock.objectId}}
                          ORDER BY qc.{{QbPublicationCard.wentOnAt}} DESC LIMIT 1)
        ELSE (SELECT sp.{{QbSponsor.company}} FROM {{QbSponsor}} sp
              WHERE sp.{{QbSponsor.userId}} = b.{{QbBlock.objectId}})
    END AS varchar(200))    AS name,
    pc.{{QbPublicationCard.publicationId}} AS publicationId,
    b.{{QbBlock.createdAt}} AS createdAt
FROM {{QbBlock}} b
LEFT JOIN {{QbPublicationCard}} pc ON pc.{{QbPublicationCard.publicationId}}
    = CAST(CASE WHEN b.{{QbBlock.kind}} = 'PUBLICATION' THEN b.{{QbBlock.objectId}} END AS uuid)
