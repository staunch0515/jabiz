/*---
id: qb.sponsor.conversations-unread
description: "How many of the sponsor's conversations have a taker's message the sponsor has not read (Q82; the menu's badge), at most 99"
entities: [QbConversation]
datasets:
  QbConversation: urn:jabiz:dataset:sponsor:QbConversation
results:
  unread: { kind: { type: numeric, precision: 3, scale: 0 } }
permissions: [qb.publication.write]
---*/
-- ConversationRules.unread for the sponsor's side, as qb.sponsor.conversations; through the sponsor's partial list
-- index (publisher, status, last message).
SELECT count(*) AS unread
FROM (
    SELECT 1
    FROM {{QbConversation}} c
    WHERE c.{{QbConversation.lastSender}} <> 'SPONSOR'
      AND (c.{{QbConversation.sponsorReadAt}} IS NULL
           OR c.{{QbConversation.sponsorReadAt}} < c.{{QbConversation.lastMessageAt}})
    LIMIT 99
) u
