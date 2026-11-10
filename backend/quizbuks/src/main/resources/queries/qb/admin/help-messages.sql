/*---
id: qb.admin.help-messages
description: >-
  F-C10: the messages of an AI help conversation handed to a person, oldest first (the conversation's page of the people
  answering it): the questions and the AI's answers before it was handed over, the user's words and the replies
entities: [QbHelpThread, QbHelpMessage]
datasets:
  QbHelpThread: urn:jabiz:dataset:staff:QbHelpThread
  QbHelpMessage: urn:jabiz:dataset:default:QbHelpMessage
params:
  threadId: { kind: { type: text, maxLength: 36 }, required: true, description: The conversation }
results:
  messageId: { from: QbHelpMessage.messageId }
  kind:      { from: QbHelpMessage.kind }
  body:      { from: QbHelpMessage.body }
  links:     { from: QbHelpMessage.links }
  page:      { from: QbHelpMessage.page }
  jobStatus: { from: QbHelpMessage.jobStatus }
  rating:    { from: QbHelpMessage.rating }
  createdAt: { from: QbHelpMessage.createdAt }
list:
  sorts: [createdAt]
  defaultSort: { field: createdAt, asc: true }
  key: [messageId]
permissions: [qb.admin.help]
---*/
-- Only through a conversation handed to a person and within the caller's scope (the staff's view).
SELECT
    m.{{QbHelpMessage.messageId}} AS messageId,
    m.{{QbHelpMessage.kind}}      AS kind,
    m.{{QbHelpMessage.body}}      AS body,
    m.{{QbHelpMessage.links}}     AS links,
    m.{{QbHelpMessage.page}}      AS page,
    m.{{QbHelpMessage.jobStatus}} AS jobStatus,
    m.{{QbHelpMessage.rating}}    AS rating,
    m.{{QbHelpMessage.createdAt}} AS createdAt
FROM {{QbHelpThread}} t
JOIN {{QbHelpMessage}} m ON m.{{QbHelpMessage.threadId}} = CAST(t.{{QbHelpThread.threadId}} AS text)
WHERE CAST(t.{{QbHelpThread.threadId}} AS text) = CAST(:threadId AS text)
