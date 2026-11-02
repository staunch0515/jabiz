/*---
id: qb.app.conversations
description: "The taker's conversations with sponsors (Q82): about which quiz, with which company, the last message and whether the sponsor wrote since the taker last read; newest first"
entities: [QbConversation, QbConversationMessage, QbPublicationCard, QbSponsor]
datasets:
  QbConversation: urn:jabiz:dataset:taker:QbConversation
  QbConversationMessage: urn:jabiz:dataset:default:QbConversationMessage
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  conversationId: { kind: { type: text, maxLength: 36 }, description: "Only this conversation (the conversation page's heading)" }
  publicationId:  { like: QbConversation.publicationId, description: "Only the conversation about this quiz (the result page's button)" }
results:
  conversationId:  { from: QbConversation.conversationId }
  publicationId:   { from: QbConversation.publicationId }
  publicationName: { from: QbPublicationCard.name }
  sponsorCompany:  { from: QbSponsor.company }
  status:          { from: QbConversation.status }
  closedBy:        { from: QbConversation.closedBy }
  openedAt:        { from: QbConversation.openedAt }
  lastMessageAt:   { from: QbConversation.lastMessageAt }
  lastSender:      { from: QbConversation.lastSender }
  lastBody:        { from: QbConversationMessage.body }
  unread:          { kind: { type: bool } }
list:
  filters: [status, lastMessageAt]
  sorts:   [lastMessageAt, openedAt]
  defaultSort: { field: lastMessageAt, asc: false }
  key: [conversationId]
permissions: [qb.conversation.use]
---*/
-- The view holds the caller's conversations as taker only. The sponsor shows as their company, never as a user
-- (D-Q82-8); unread is ConversationRules.unread for the taker's side.
SELECT
    c.{{QbConversation.conversationId}} AS conversationId,
    c.{{QbConversation.publicationId}}  AS publicationId,
    p.{{QbPublicationCard.name}}        AS publicationName,
    sp.{{QbSponsor.company}}            AS sponsorCompany,
    c.{{QbConversation.status}}         AS status,
    c.{{QbConversation.closedBy}}       AS closedBy,
    c.{{QbConversation.openedAt}}       AS openedAt,
    c.{{QbConversation.lastMessageAt}}  AS lastMessageAt,
    c.{{QbConversation.lastSender}}     AS lastSender,
    m.last_body                         AS lastBody,
    (c.{{QbConversation.lastSender}} <> 'TAKER'
     AND (c.{{QbConversation.takerReadAt}} IS NULL
          OR c.{{QbConversation.takerReadAt}} < c.{{QbConversation.lastMessageAt}})) AS unread
FROM {{QbConversation}} c
LEFT JOIN {{QbPublicationCard}} p ON p.{{QbPublicationCard.publicationId}} = c.{{QbConversation.publicationId}}
LEFT JOIN {{QbSponsor}} sp ON c.{{QbConversation.publisherKind}} = 'SPONSOR'
    AND sp.{{QbSponsor.userId}} = c.{{QbConversation.publisherId}}
LEFT JOIN LATERAL (
    SELECT x.{{QbConversationMessage.body}} AS last_body
    FROM {{QbConversationMessage}} x
    WHERE x.{{QbConversationMessage.conversationId}} = c.{{QbConversation.conversationId}}
    ORDER BY x.{{QbConversationMessage.createdAt}} DESC
    LIMIT 1
) m ON true
WHERE (CAST(:conversationId AS text) IS NULL OR c.{{QbConversation.conversationId}} = CAST(:conversationId AS text))
  AND (CAST(:publicationId AS uuid) IS NULL OR c.{{QbConversation.publicationId}} = CAST(:publicationId AS uuid))
