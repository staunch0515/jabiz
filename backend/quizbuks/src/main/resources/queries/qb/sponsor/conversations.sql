/*---
id: qb.sponsor.conversations
description: "The sponsor's conversations with takers (Q82): the taker's name, the quiz, the last message and whether the taker wrote since the sponsor last read; those waiting for a reply first, then newest"
entities: [QbConversation, QbConversationMessage, QbPublicationCard, QbProfile]
datasets:
  QbConversation: urn:jabiz:dataset:sponsor:QbConversation
  QbConversationMessage: urn:jabiz:dataset:default:QbConversationMessage
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbProfile: urn:jabiz:dataset:default:QbProfile
params:
  conversationId: { kind: { type: text, maxLength: 36 }, description: "Only this conversation (the conversation page's heading)" }
  waiting:        { kind: { type: bool }, description: "Only open conversations whose last message is the taker's (true)" }
results:
  conversationId:  { from: QbConversation.conversationId }
  publicationId:   { from: QbConversation.publicationId }
  publicationName: { from: QbPublicationCard.name }
  takerName:       { from: QbProfile.displayName }
  status:          { from: QbConversation.status }
  closedBy:        { from: QbConversation.closedBy }
  openedAt:        { from: QbConversation.openedAt }
  lastMessageAt:   { from: QbConversation.lastMessageAt }
  lastSender:      { from: QbConversation.lastSender }
  lastBody:        { from: QbConversationMessage.body }
  unread:          { kind: { type: bool } }
  waitingReply:    { kind: { type: bool } }
list:
  filters: [status, publicationId, lastMessageAt]
  sorts:   [waitingReply, lastMessageAt, openedAt]
  defaultSort: { field: lastMessageAt, asc: false }
  key: [conversationId]
permissions: [qb.publication.write]
---*/
-- The view holds the sponsor's own conversations (theirs as publisher). The taker shows by their profile's name, never
-- their address or id (D-Q82-8); unread is ConversationRules.unread for the sponsor's side; waitingReply an open one
-- the taker wrote last in (the page puts them first, D-Q82 risk "unanswered").
SELECT
    c.{{QbConversation.conversationId}} AS conversationId,
    c.{{QbConversation.publicationId}}  AS publicationId,
    p.{{QbPublicationCard.name}}        AS publicationName,
    t.{{QbProfile.displayName}}         AS takerName,
    c.{{QbConversation.status}}         AS status,
    c.{{QbConversation.closedBy}}       AS closedBy,
    c.{{QbConversation.openedAt}}       AS openedAt,
    c.{{QbConversation.lastMessageAt}}  AS lastMessageAt,
    c.{{QbConversation.lastSender}}     AS lastSender,
    m.last_body                         AS lastBody,
    (c.{{QbConversation.lastSender}} <> 'SPONSOR'
     AND (c.{{QbConversation.sponsorReadAt}} IS NULL
          OR c.{{QbConversation.sponsorReadAt}} < c.{{QbConversation.lastMessageAt}})) AS unread,
    (c.{{QbConversation.status}} = 'OPEN' AND c.{{QbConversation.lastSender}} = 'TAKER') AS waitingReply
FROM {{QbConversation}} c
LEFT JOIN {{QbPublicationCard}} p ON p.{{QbPublicationCard.publicationId}} = c.{{QbConversation.publicationId}}
LEFT JOIN {{QbProfile}} t ON t.{{QbProfile.userId}} = c.{{QbConversation.takerId}}
LEFT JOIN LATERAL (
    SELECT x.{{QbConversationMessage.body}} AS last_body
    FROM {{QbConversationMessage}} x
    WHERE x.{{QbConversationMessage.conversationId}} = c.{{QbConversation.conversationId}}
    ORDER BY x.{{QbConversationMessage.createdAt}} DESC
    LIMIT 1
) m ON true
WHERE (CAST(:conversationId AS text) IS NULL OR c.{{QbConversation.conversationId}} = CAST(:conversationId AS text))
  AND (CAST(:waiting AS boolean) IS NULL OR CAST(:waiting AS boolean) = false
       OR c.{{QbConversation.status}} = 'OPEN' AND c.{{QbConversation.lastSender}} = 'TAKER')
