/*---
id: qb.sponsor.conversation-messages
description: "The messages of one of the sponsor's conversations (Q82), oldest first: which side wrote what and when"
entities: [QbConversation, QbConversationMessage]
datasets:
  QbConversation: urn:jabiz:dataset:sponsor:QbConversation
  QbConversationMessage: urn:jabiz:dataset:default:QbConversationMessage
params:
  conversationId: { kind: { type: text, maxLength: 36 }, required: true, description: The conversation }
results:
  messageId: { from: QbConversationMessage.messageId }
  sender:    { from: QbConversationMessage.sender }
  body:      { from: QbConversationMessage.body }
  createdAt: { from: QbConversationMessage.createdAt }
list:
  sorts: [createdAt]
  defaultSort: { field: createdAt, asc: true }
  key: [messageId]
permissions: [qb.publication.write]
---*/
-- Only through the sponsor's own conversation (the view); the sender's user is never returned (D-Q82-8).
SELECT
    m.{{QbConversationMessage.messageId}} AS messageId,
    m.{{QbConversationMessage.sender}}    AS sender,
    m.{{QbConversationMessage.body}}      AS body,
    m.{{QbConversationMessage.createdAt}} AS createdAt
FROM {{QbConversation}} c
JOIN {{QbConversationMessage}} m
    ON m.{{QbConversationMessage.conversationId}} = c.{{QbConversation.conversationId}}
WHERE c.{{QbConversation.conversationId}} = CAST(:conversationId AS text)
