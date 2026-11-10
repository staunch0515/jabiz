/*---
id: qb.me.help-messages
description: >-
  F-C10: the messages of one of the caller's AI help conversations, oldest first: the questions with their answer's
  state (the pages ask again while it is QUEUED), the AI's answers with their same-site links, the words to a person and
  the person's replies, each answer's rating. Never who replied
entities: [QbHelpThread, QbHelpMessage]
datasets:
  QbHelpThread: urn:jabiz:dataset:me:QbHelpThread
  QbHelpMessage: urn:jabiz:dataset:me:QbHelpMessage
params:
  threadId: { kind: { type: text, maxLength: 36 }, required: true, description: The conversation }
results:
  messageId:   { from: QbHelpMessage.messageId }
  kind:        { from: QbHelpMessage.kind }
  body:        { from: QbHelpMessage.body }
  links:       { from: QbHelpMessage.links }
  jobStatus:   { from: QbHelpMessage.jobStatus }
  failureCode: { from: QbHelpMessage.failureCode }
  rating:      { from: QbHelpMessage.rating }
  createdAt:   { from: QbHelpMessage.createdAt }
list:
  sorts: [createdAt]
  defaultSort: { field: createdAt, asc: true }
  key: [messageId]
permissions: [qb.help.use]
---*/
-- Only through the caller's own conversation (the views). Links are paths of the site, one a line.
SELECT
    m.{{QbHelpMessage.messageId}}   AS messageId,
    m.{{QbHelpMessage.kind}}        AS kind,
    m.{{QbHelpMessage.body}}        AS body,
    m.{{QbHelpMessage.links}}       AS links,
    m.{{QbHelpMessage.jobStatus}}   AS jobStatus,
    m.{{QbHelpMessage.failureCode}} AS failureCode,
    m.{{QbHelpMessage.rating}}      AS rating,
    m.{{QbHelpMessage.createdAt}}   AS createdAt
FROM {{QbHelpThread}} t
JOIN {{QbHelpMessage}} m ON m.{{QbHelpMessage.threadId}} = CAST(t.{{QbHelpThread.threadId}} AS text)
WHERE CAST(t.{{QbHelpThread.threadId}} AS text) = CAST(:threadId AS text)
