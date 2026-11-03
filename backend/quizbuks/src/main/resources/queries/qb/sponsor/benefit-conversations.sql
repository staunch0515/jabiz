/*---
id: qb.sponsor.benefit-conversations
description: "Q89a: the caller's conversations about others' benefits it may buy, newest first, with the provider's company"
entities: [QbBenefitConversation, QbBenefitMessage, QbBenefitOffer, QbSponsor]
datasets:
  QbBenefitConversation: urn:jabiz:dataset:sponsor:QbBenefitConversation
  QbBenefitMessage: urn:jabiz:dataset:default:QbBenefitMessage
  QbBenefitOffer: urn:jabiz:dataset:default:QbBenefitOffer
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  conversationId: { kind: { type: text, maxLength: 36 }, description: "Only this conversation (the conversation page's heading)" }
results:
  conversationId: { from: QbBenefitConversation.conversationId }
  offerId:        { from: QbBenefitConversation.offerId }
  offerName:      { from: QbBenefitOffer.name }
  otherCompany:   { from: QbSponsor.company }
  openedAt:       { from: QbBenefitConversation.openedAt }
  lastMessageAt:  { from: QbBenefitConversation.lastMessageAt }
  lastSender:     { from: QbBenefitConversation.lastSender }
  lastBody:       { from: QbBenefitMessage.body }
  unread:         { kind: { type: bool } }
list:
  filters: [lastMessageAt]
  sorts:   [lastMessageAt, openedAt]
  defaultSort: { field: lastMessageAt, asc: false }
  key: [conversationId]
permissions: [qb.benefit.use]
---*/
-- The view holds the caller's conversations as the sponsor (Q89a, D-Q89-4); the other side shows by its company. Unread
-- is BenefitRules.unread for the caller's side.
SELECT
    c.{{QbBenefitConversation.conversationId}} AS conversationId,
    c.{{QbBenefitConversation.offerId}}        AS offerId,
    o.{{QbBenefitOffer.name}}                  AS offerName,
    sp.{{QbSponsor.company}}                   AS otherCompany,
    c.{{QbBenefitConversation.openedAt}}       AS openedAt,
    c.{{QbBenefitConversation.lastMessageAt}}  AS lastMessageAt,
    c.{{QbBenefitConversation.lastSender}}     AS lastSender,
    m.last_body                                AS lastBody,
    (c.{{QbBenefitConversation.lastSender}} <> 'SPONSOR'
     AND (c.{{QbBenefitConversation.sponsorReadAt}} IS NULL
          OR c.{{QbBenefitConversation.sponsorReadAt}} < c.{{QbBenefitConversation.lastMessageAt}})) AS unread
FROM {{QbBenefitConversation}} c
LEFT JOIN {{QbBenefitOffer}} o ON o.{{QbBenefitOffer.offerId}} = c.{{QbBenefitConversation.offerId}}
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = c.{{QbBenefitConversation.providerId}}
LEFT JOIN LATERAL (
    SELECT x.{{QbBenefitMessage.body}} AS last_body
    FROM {{QbBenefitMessage}} x
    WHERE x.{{QbBenefitMessage.conversationId}} = c.{{QbBenefitConversation.conversationId}}
    ORDER BY x.{{QbBenefitMessage.createdAt}} DESC
    LIMIT 1
) m ON true
WHERE (CAST(:conversationId AS text) IS NULL OR c.{{QbBenefitConversation.conversationId}} = CAST(:conversationId AS text))
