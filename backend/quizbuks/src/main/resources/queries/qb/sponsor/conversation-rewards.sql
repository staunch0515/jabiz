/*---
id: qb.sponsor.conversation-rewards
description: "What the taker of one of the sponsor's conversations won on its quiz (Q82, D-Q82-13): the cash rewards, prizes to collect and digital prizes the sponsor settles, with their state"
entities: [QbConversation, QbCashReward, QbRedemption, QbDigitalPrize]
datasets:
  QbConversation: urn:jabiz:dataset:sponsor:QbConversation
  QbCashReward: urn:jabiz:dataset:sponsor:QbCashReward
  QbRedemption: urn:jabiz:dataset:sponsor:QbRedemption
  QbDigitalPrize: urn:jabiz:dataset:sponsor:QbDigitalPrize
params:
  conversationId: { kind: { type: text, maxLength: 36 }, required: true, description: The conversation }
results:
  kind:       { kind: { type: text, maxLength: 10 } }
  recordId:   { kind: { type: text, maxLength: 36 } }
  sourceKind: { from: QbCashReward.sourceKind }
  code:       { from: QbCashReward.code }
  hint:       { from: QbDigitalPrize.hint }
  amount:     { from: QbCashReward.amount }
  status:     { kind: { type: text, maxLength: 20 } }
list:
  sorts: [kind]
  defaultSort: { field: kind, asc: true }
  key: [kind, recordId]
permissions: [qb.publication.write]
---*/
-- Only the conversation's quiz and taker, only the sponsor's own records (the views); the platform's Kudos reward is
-- the platform's to pay and not here. A referral's reward (sourceKind REFERRAL) counts too: it is about the same quiz.
-- A digital prize shows its hint (the last characters), never its content.
SELECT 'CASH' AS kind, CAST(r.{{QbCashReward.cashRewardId}} AS text) AS recordId,
       r.{{QbCashReward.sourceKind}} AS sourceKind, r.{{QbCashReward.code}} AS code, CAST(NULL AS text) AS hint,
       r.{{QbCashReward.amount}} AS amount, r.{{QbCashReward.status}} AS status
FROM {{QbConversation}} c
JOIN {{QbCashReward}} r ON r.{{QbCashReward.userId}} = c.{{QbConversation.takerId}}
    AND r.{{QbCashReward.publicationId}} = c.{{QbConversation.publicationId}}
WHERE c.{{QbConversation.conversationId}} = CAST(:conversationId AS text)
UNION ALL
SELECT 'REDEEM', CAST(d.{{QbRedemption.redemptionId}} AS text), d.{{QbRedemption.sourceKind}},
       d.{{QbRedemption.code}}, CAST(NULL AS text), d.{{QbRedemption.amount}}, d.{{QbRedemption.status}}
FROM {{QbConversation}} c
JOIN {{QbRedemption}} d ON d.{{QbRedemption.userId}} = c.{{QbConversation.takerId}}
    AND d.{{QbRedemption.publicationId}} = c.{{QbConversation.publicationId}}
WHERE c.{{QbConversation.conversationId}} = CAST(:conversationId AS text)
UNION ALL
SELECT 'DIGITAL', CAST(g.{{QbDigitalPrize.digitalPrizeId}} AS text), g.{{QbDigitalPrize.sourceKind}},
       CAST(NULL AS text), g.{{QbDigitalPrize.hint}}, g.{{QbDigitalPrize.amount}}, g.{{QbDigitalPrize.status}}
FROM {{QbConversation}} c
JOIN {{QbDigitalPrize}} g ON g.{{QbDigitalPrize.userId}} = c.{{QbConversation.takerId}}
    AND g.{{QbDigitalPrize.publicationId}} = c.{{QbConversation.publicationId}}
WHERE c.{{QbConversation.conversationId}} = CAST(:conversationId AS text)
