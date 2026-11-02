/*---
id: qb.system.conversations-due
description: Open conversations without a message for qb.conversation.close-days days, oldest first (the items of the job qb.conversation-expire)
entities: [QbConversation, SysParam]
datasets:
  QbConversation: urn:jabiz:dataset:default:QbConversation
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at: { like: QbConversation.lastMessageAt, required: true, description: The scheduled time }
results:
  conversationId: { from: QbConversation.conversationId }
  lastMessageAt:  { from: QbConversation.lastMessageAt }
list:
  sorts: [lastMessageAt]
  defaultSort: { field: lastMessageAt, asc: true }
  key: [conversationId]
permissions: [qb.conversation.system]
---*/
-- ConversationRules.due: the expiry reads the conversation and the parameter again under the conversation's lock. The
-- parameter is read at the server's clock; a value that is no whole positive number (or none) closes nothing.
WITH days AS (
    SELECT CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,4}$' THEN CAST(p.{{SysParam.value}} AS integer) END AS n
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.conversation.close-days'
)
SELECT c.{{QbConversation.conversationId}} AS conversationId, c.{{QbConversation.lastMessageAt}} AS lastMessageAt
FROM {{QbConversation}} c, days d
WHERE c.{{QbConversation.status}} = 'OPEN'
  AND d.n > 0
  AND c.{{QbConversation.lastMessageAt}} <= CAST(:at AS timestamptz) - make_interval(days => d.n)
