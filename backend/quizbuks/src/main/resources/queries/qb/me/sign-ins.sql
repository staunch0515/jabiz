/*---
id: qb.me.sign-ins
description: The caller's own sign-in records (M-02), newest first, with the entry, the address and the device
entities: [SecLoginRecord]
datasets:
  SecLoginRecord: urn:jabiz:dataset:me:SecLoginRecord
results:
  loginRecordId: { from: SecLoginRecord.loginRecordId }
  attemptTime:   { from: SecLoginRecord.attemptTime }
  outcome:       { from: SecLoginRecord.outcome }
  entry:         { from: SecLoginRecord.entry }
  factor:        { from: SecLoginRecord.factor }
  clientIp:      { from: SecLoginRecord.clientIp }
  userAgent:     { from: SecLoginRecord.userAgent }
list:
  filters: [entry, outcome, attemptTime]
  sorts:   [attemptTime]
  defaultSort: { field: attemptTime, asc: false }
  key: [loginRecordId]
permissions: [qb.account.read]
---*/
-- The dataset holds the caller's records only: its scope is the user id of the request.
SELECT
    r.{{SecLoginRecord.loginRecordId}} AS loginRecordId,
    r.{{SecLoginRecord.attemptTime}}   AS attemptTime,
    r.{{SecLoginRecord.outcome}}       AS outcome,
    r.{{SecLoginRecord.entry}}         AS entry,
    r.{{SecLoginRecord.factor}}        AS factor,
    r.{{SecLoginRecord.clientIp}}      AS clientIp,
    r.{{SecLoginRecord.userAgent}}     AS userAgent
FROM {{SecLoginRecord}} r
