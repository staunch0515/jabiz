/*---
id: qb.app.conversations-unread
description: "How many of the taker's conversations have a sponsor's message the taker has not read (Q82), at most 99"
entities: [QbConversation]
datasets:
  QbConversation: urn:jabiz:dataset:taker:QbConversation
results:
  unread: { kind: { type: numeric, precision: 3, scale: 0 } }
permissions: [qb.conversation.use]
---*/
-- ConversationRules.unread for the taker's side, as qb.app.conversations.
SELECT count(*) AS unread
FROM (
    SELECT 1
    FROM {{QbConversation}} c
    WHERE c.{{QbConversation.lastSender}} <> 'TAKER'
      AND (c.{{QbConversation.takerReadAt}} IS NULL
           OR c.{{QbConversation.takerReadAt}} < c.{{QbConversation.lastMessageAt}})
    LIMIT 99
) u
