/*---
id: qb.sponsor.benefit-messages
description: "Q89a: the messages of one of the caller's conversations as the sponsor, oldest first"
entities: [QbBenefitMessage]
datasets:
  QbBenefitMessage: urn:jabiz:dataset:sponsor:QbBenefitMessage
params:
  conversationId: { kind: { type: text, maxLength: 36 }, required: true, description: The conversation }
results:
  messageId: { from: QbBenefitMessage.messageId }
  sender:    { from: QbBenefitMessage.sender }
  body:      { from: QbBenefitMessage.body }
  createdAt: { from: QbBenefitMessage.createdAt }
  mine:      { kind: { type: bool } }
list:
  sorts: [createdAt]
  defaultSort: { field: createdAt, asc: true }
  key: [messageId]
permissions: [qb.benefit.use]
---*/
-- The view holds the messages of the caller's conversations as the sponsor; who wrote is a side, never a user.
SELECT
    m.{{QbBenefitMessage.messageId}} AS messageId,
    m.{{QbBenefitMessage.sender}}    AS sender,
    m.{{QbBenefitMessage.body}}      AS body,
    m.{{QbBenefitMessage.createdAt}} AS createdAt,
    (m.{{QbBenefitMessage.sender}} = 'SPONSOR') AS mine
FROM {{QbBenefitMessage}} m
WHERE m.{{QbBenefitMessage.conversationId}} = CAST(:conversationId AS text)
