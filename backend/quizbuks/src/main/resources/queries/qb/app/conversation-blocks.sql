/*---
id: qb.app.conversation-blocks
description: "The sponsors the taker blocked (Q82), newest first: the company and since when"
entities: [QbConversationBlock, QbSponsor]
datasets:
  QbConversationBlock: urn:jabiz:dataset:taker:QbConversationBlock
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
results:
  blockId:        { from: QbConversationBlock.blockId }
  sponsorCompany: { from: QbSponsor.company }
  createdAt:      { from: QbConversationBlock.createdAt }
list:
  sorts: [createdAt]
  defaultSort: { field: createdAt, asc: false }
  key: [blockId]
permissions: [qb.conversation.use]
---*/
SELECT
    b.{{QbConversationBlock.blockId}}   AS blockId,
    sp.{{QbSponsor.company}}            AS sponsorCompany,
    b.{{QbConversationBlock.createdAt}} AS createdAt
FROM {{QbConversationBlock}} b
LEFT JOIN {{QbSponsor}} sp ON b.{{QbConversationBlock.publisherKind}} = 'SPONSOR'
    AND sp.{{QbSponsor.userId}} = b.{{QbConversationBlock.publisherId}}
