/*---
id: finance.gl.journal_register
description: >-
  Journal entries with a posting date in a range: numbers, dates, source, state, totals, preparer, the approvers of an
  approved entry and the general ledger number once posted (FIN-GL-010, FIN-GL-013, FIN-RP-008).
entities: [FinJournal, SysApprovalRequest, ApprovalDecision]
params:
  from:   { like: FinJournal.postingDate, required: true, description: first posting date }
  to:     { like: FinJournal.postingDate, required: true, description: last posting date }
  status: { like: FinJournal.status, description: "only entries in this state, if given" }
results:
  journalId:    { from: FinJournal.journalId }
  journalNo:    { from: FinJournal.journalNo }
  postingDate:  { from: FinJournal.postingDate }
  documentDate: { from: FinJournal.documentDate }
  description:  { from: FinJournal.description }
  source:       { from: FinJournal.source }
  status:       { from: FinJournal.status }
  totalDebit:   { from: FinJournal.totalDebit }
  totalCredit:  { from: FinJournal.totalCredit }
  preparer:     { from: FinJournal.preparer }
  approver:     { kind: { type: text, maxLength: 500 } }
  periodKey:    { from: FinJournal.periodKey }
  glNo:         { from: FinJournal.glNo }
list:
  filters: [journalNo, source, status, preparer, approver, periodKey, glNo]
  sorts:   [journalNo, postingDate, glNo, totalDebit]
  defaultSort: { field: postingDate, asc: true }
  key: [journalId]
permissions: [fin.journal.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
SELECT
    j.{{FinJournal.journalId}}    AS journalId,
    j.{{FinJournal.journalNo}}    AS journalNo,
    j.{{FinJournal.postingDate}}  AS postingDate,
    j.{{FinJournal.documentDate}} AS documentDate,
    j.{{FinJournal.description}}  AS description,
    j.{{FinJournal.source}}       AS source,
    j.{{FinJournal.status}}       AS status,
    j.{{FinJournal.totalDebit}}   AS totalDebit,
    j.{{FinJournal.totalCredit}}  AS totalCredit,
    j.{{FinJournal.preparer}}     AS preparer,
    (SELECT STRING_AGG(DISTINCT d.{{ApprovalDecision.approverId}}, ', ')
     FROM {{SysApprovalRequest}} q
     JOIN {{ApprovalDecision}} d ON d.{{ApprovalDecision.requestId}} = q.{{SysApprovalRequest.requestId}}
     WHERE q.{{SysApprovalRequest.entityId}} = CAST(j.{{FinJournal.journalId}} AS text)
       AND q.{{SysApprovalRequest.status}} = 'APPROVED'
       AND d.{{ApprovalDecision.decision}} = 'APPROVE') AS approver,
    j.{{FinJournal.periodKey}}    AS periodKey,
    j.{{FinJournal.glNo}}         AS glNo
FROM {{FinJournal}} j
WHERE j.{{FinJournal.postingDate}} BETWEEN :from AND :to
  AND (CAST(:status AS text) IS NULL OR j.{{FinJournal.status}} = :status)
