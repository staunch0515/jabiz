/*---
id: qb.admin.help-gaps
description: >-
  F-C10: what the AI help did not solve in a period (a month), for the content administrators to add to the FAQ and the
  manual: each question whose answer was rated "not solved", and each conversation handed to a person with its first
  question; the entry, the language and the page it was asked on. Questions only: never who asked
entities: [QbHelpThread, QbHelpMessage]
datasets:
  QbHelpThread: urn:jabiz:dataset:default:QbHelpThread
  QbHelpMessage: urn:jabiz:dataset:default:QbHelpMessage
params:
  month: { kind: { type: text, maxLength: 7 }, required: true, description: "The month (YYYY-MM, the deployment's zone: jabiz.fiscal-year-zone, as the processes record it)" }
results:
  rowKey:   { kind: { type: text, maxLength: 60 } }
  reason:   { kind: { type: text, maxLength: 10 } }
  threadId: { from: QbHelpThread.threadId }
  entry:    { from: QbHelpThread.entry }
  language: { from: QbHelpThread.language }
  page:     { from: QbHelpMessage.page }
  question: { from: QbHelpMessage.body }
  at:       { kind: { type: temporal, role: EVENT_TIME } }
list:
  filters: [reason, entry, language, page]
  sorts:   [at, page]
  defaultSort: { field: at, asc: false }
  key: [rowKey]
permissions: [qb.admin.help.gaps]
---*/
-- An unsolved question: the first answer after it was rated "not solved" (rated within the period). A conversation
-- handed over within the period: its first question, or the words handed over directly when it had none.
WITH unsolved AS (
    SELECT 'U:' || CAST(q.{{QbHelpMessage.messageId}} AS text) AS rowKey, 'UNSOLVED' AS reason,
           t.{{QbHelpThread.threadId}} AS threadId, t.{{QbHelpThread.entry}} AS entry,
           t.{{QbHelpThread.language}} AS language, q.{{QbHelpMessage.page}} AS page,
           q.{{QbHelpMessage.body}} AS question, a.ratedAt AS at
    FROM {{QbHelpMessage}} q
    JOIN {{QbHelpThread}} t ON CAST(t.{{QbHelpThread.threadId}} AS text) = q.{{QbHelpMessage.threadId}}
    CROSS JOIN LATERAL (
        SELECT n.{{QbHelpMessage.rating}} AS rating, n.{{QbHelpMessage.ratedAt}} AS ratedAt,
               n.{{QbHelpMessage.ratedMonth}} AS ratedMonth
        FROM {{QbHelpMessage}} n
        WHERE n.{{QbHelpMessage.threadId}} = q.{{QbHelpMessage.threadId}}
          AND n.{{QbHelpMessage.kind}} = 'ANSWER'
          AND n.{{QbHelpMessage.createdAt}} >= q.{{QbHelpMessage.createdAt}}
        ORDER BY n.{{QbHelpMessage.createdAt}}
        LIMIT 1) a
    WHERE q.{{QbHelpMessage.kind}} = 'QUESTION'
      AND a.rating = 'UNSOLVED'
      AND a.ratedMonth = :month
), escalated AS (
    SELECT 'E:' || CAST(t.{{QbHelpThread.threadId}} AS text) AS rowKey, 'ESCALATED' AS reason,
           t.{{QbHelpThread.threadId}} AS threadId, t.{{QbHelpThread.entry}} AS entry,
           t.{{QbHelpThread.language}} AS language, f.page AS page, f.question AS question,
           t.{{QbHelpThread.escalatedAt}} AS at
    FROM {{QbHelpThread}} t
    CROSS JOIN LATERAL (
        SELECT m.{{QbHelpMessage.page}} AS page, m.{{QbHelpMessage.body}} AS question
        FROM {{QbHelpMessage}} m
        WHERE m.{{QbHelpMessage.threadId}} = CAST(t.{{QbHelpThread.threadId}} AS text)
          AND m.{{QbHelpMessage.kind}} IN ('QUESTION', 'USER')
        ORDER BY m.{{QbHelpMessage.createdAt}}
        LIMIT 1) f
    WHERE t.{{QbHelpThread.escalated}}
      AND t.{{QbHelpThread.escalatedMonth}} = :month
)
SELECT rowKey, reason, threadId, entry, language, page, question, at FROM unsolved
UNION ALL
SELECT rowKey, reason, threadId, entry, language, page, question, at FROM escalated
