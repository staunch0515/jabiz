/*---
id: qb.admin.help-threads
description: >-
  F-C10: the AI help conversations handed to a person: who asked (a Taker's display name, a Sponsor's company), where
  each went (an operator, or headquarters), whom it waits for and since when. Headquarters sees every one, an operator
  its own Sponsors' only
entities: [QbHelpThread, QbSponsor, QbProfile, QbOperator]
datasets:
  QbHelpThread: urn:jabiz:dataset:staff:QbHelpThread
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbProfile: urn:jabiz:dataset:default:QbProfile
  QbOperator: urn:jabiz:dataset:default:QbOperator
params:
  open: { kind: { type: bool }, description: "true: only the conversations still open" }
results:
  threadId:       { from: QbHelpThread.threadId }
  userId:         { from: QbHelpThread.userId }
  entry:          { from: QbHelpThread.entry }
  name:           { kind: { type: text, maxLength: 200 } }
  language:       { from: QbHelpThread.language }
  status:         { from: QbHelpThread.status }
  operatorId:     { from: QbHelpThread.operatorId }
  operatorName:   { from: QbOperator.name }
  waitingFor:     { from: QbHelpThread.waitingFor }
  waitingSince:   { from: QbHelpThread.waitingSince }
  escalatedAt:    { from: QbHelpThread.escalatedAt }
  closeReason:    { from: QbHelpThread.closeReason }
  lastActivityAt: { from: QbHelpThread.lastActivityAt }
list:
  filters: [status, entry, waitingFor, operatorId]
  sorts:   [waitingSince, escalatedAt, lastActivityAt]
  defaultSort: { field: lastActivityAt, asc: false }
  key: [threadId]
permissions: [qb.admin.help]
---*/
-- The view holds the conversations handed to a person only (an operator's session: its own Sponsors'). The display name
-- only: never an address or the account's other data.
SELECT
    t.{{QbHelpThread.threadId}}       AS threadId,
    t.{{QbHelpThread.userId}}         AS userId,
    t.{{QbHelpThread.entry}}          AS entry,
    CASE WHEN t.{{QbHelpThread.entry}} = 'sponsor' THEN s.{{QbSponsor.company}} ELSE p.{{QbProfile.displayName}} END
                                      AS name,
    t.{{QbHelpThread.language}}       AS language,
    t.{{QbHelpThread.status}}         AS status,
    t.{{QbHelpThread.operatorId}}     AS operatorId,
    o.{{QbOperator.name}}             AS operatorName,
    t.{{QbHelpThread.waitingFor}}     AS waitingFor,
    t.{{QbHelpThread.waitingSince}}   AS waitingSince,
    t.{{QbHelpThread.escalatedAt}}    AS escalatedAt,
    t.{{QbHelpThread.closeReason}}    AS closeReason,
    t.{{QbHelpThread.lastActivityAt}} AS lastActivityAt
FROM {{QbHelpThread}} t
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = t.{{QbHelpThread.userId}}
LEFT JOIN {{QbProfile}} p ON CAST(p.{{QbProfile.userId}} AS text) = t.{{QbHelpThread.userId}}
LEFT JOIN {{QbOperator}} o ON CAST(o.{{QbOperator.operatorId}} AS text) = t.{{QbHelpThread.operatorId}}
WHERE (CAST(:open AS boolean) IS NULL OR NOT CAST(:open AS boolean)
       OR t.{{QbHelpThread.status}} <> 'CLOSED')
