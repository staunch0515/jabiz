/*---
id: qb.me.help-threads
description: >-
  F-C10: the caller's AI help conversations of one entry (a Taker's in the app, a Sponsor's on the Sponsor pages),
  newest first: where each stands, whether it went to a person and what its first question was; or one of them (the
  list's filter on threadId: the conversation's page, F-C10c)
entities: [QbHelpThread, QbHelpMessage]
datasets:
  QbHelpThread: urn:jabiz:dataset:me:QbHelpThread
  QbHelpMessage: urn:jabiz:dataset:me:QbHelpMessage
params:
  entry: { like: QbHelpThread.entry, required: true, description: "app (the Taker's) or sponsor (the Sponsor's)" }
results:
  threadId:       { from: QbHelpThread.threadId }
  status:         { from: QbHelpThread.status }
  escalated:      { from: QbHelpThread.escalated }
  waitingFor:     { from: QbHelpThread.waitingFor }
  resolved:       { from: QbHelpThread.resolved }
  closeReason:    { from: QbHelpThread.closeReason }
  createdAt:      { from: QbHelpThread.createdAt }
  lastActivityAt: { from: QbHelpThread.lastActivityAt }
  firstQuestion:  { kind: { type: text, maxLength: 8000 } }
list:
  filters: [status, threadId]
  sorts:   [lastActivityAt, createdAt]
  defaultSort: { field: lastActivityAt, asc: false }
  key: [threadId]
permissions: [qb.help.use]
---*/
-- Only the caller's own (the views). The first message is the first question, or what was handed to a person directly.
SELECT
    t.{{QbHelpThread.threadId}}       AS threadId,
    t.{{QbHelpThread.status}}         AS status,
    t.{{QbHelpThread.escalated}}      AS escalated,
    t.{{QbHelpThread.waitingFor}}     AS waitingFor,
    t.{{QbHelpThread.resolved}}       AS resolved,
    t.{{QbHelpThread.closeReason}}    AS closeReason,
    t.{{QbHelpThread.createdAt}}      AS createdAt,
    t.{{QbHelpThread.lastActivityAt}} AS lastActivityAt,
    (SELECT m.{{QbHelpMessage.body}} FROM {{QbHelpMessage}} m
     WHERE m.{{QbHelpMessage.threadId}} = CAST(t.{{QbHelpThread.threadId}} AS text)
       AND m.{{QbHelpMessage.kind}} IN ('QUESTION', 'USER')
     ORDER BY m.{{QbHelpMessage.createdAt}} LIMIT 1) AS firstQuestion
FROM {{QbHelpThread}} t
WHERE t.{{QbHelpThread.entry}} = :entry
