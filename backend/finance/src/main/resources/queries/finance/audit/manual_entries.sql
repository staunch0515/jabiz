/*---
id: finance.audit.manual_entries
description: >-
  For an audit request (FIN-CT-012, FIN-SCN-12): the posted journal entries people made (every source but the
  opening, closing and automatic reversing entries the books make themselves and the payroll provider's journal) with
  a posting date in a range and a
  total above an amount, each with its preparer, how the approval rules decided it (the outcome and the rule versions
  of its latest evaluation, FIN-CT-002) and who approved it when.
entities: [FinJournal, SysApprovalRequest, ApprovalDecision, ApprovalEvaluation]
params:
  from:      { like: FinJournal.postingDate, required: true, description: first posting date }
  to:        { like: FinJournal.postingDate, required: true, description: last posting date }
  minAmount: { like: FinJournal.totalDebit, required: true, description: "entries above this total" }
results:
  journalId:    { from: FinJournal.journalId }
  journalNo:    { from: FinJournal.journalNo }
  postingDate:  { from: FinJournal.postingDate }
  source:       { from: FinJournal.source }
  description:  { from: FinJournal.description }
  amount:       { from: FinJournal.totalDebit }
  preparer:     { from: FinJournal.preparer }
  outcome:      { from: ApprovalEvaluation.outcome }
  ruleVersions: { from: ApprovalEvaluation.ruleVersions }
  approver:     { kind: { type: text, maxLength: 500 } }
  approvedTime: { from: ApprovalDecision.decidedTime }
  glNo:         { from: FinJournal.glNo }
list:
  filters: [journalNo, source, preparer, approver, outcome]
  sorts:   [journalNo, postingDate, amount, glNo]
  defaultSort: { field: postingDate, asc: true }
  key: [journalId]
permissions: [fin.journal.read, approval.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
WITH entries AS (
    SELECT j.*
    FROM {{FinJournal}} j
    WHERE j.{{FinJournal.postingDate}} BETWEEN :from AND :to
      AND j.{{FinJournal.status}} = 'POSTED'
      AND j.{{FinJournal.source}} NOT IN ('OPENING', 'CLOSING', 'AUTO_REVERSING', 'PAYROLL')
      AND j.{{FinJournal.totalDebit}} > :minAmount
),
decided AS (
    SELECT q.{{SysApprovalRequest.entityId}} AS journal_ref,
           STRING_AGG(DISTINCT d.{{ApprovalDecision.approverId}}, ', '
               ORDER BY d.{{ApprovalDecision.approverId}}) AS approver,
           MAX(d.{{ApprovalDecision.decidedTime}}) AS approved_time
    FROM {{SysApprovalRequest}} q
    JOIN {{ApprovalDecision}} d ON d.{{ApprovalDecision.requestId}} = q.{{SysApprovalRequest.requestId}}
    WHERE q.{{SysApprovalRequest.subject}} = 'fin.journal'
      AND q.{{SysApprovalRequest.status}} = 'APPROVED'
      AND d.{{ApprovalDecision.decision}} = 'APPROVE'
      AND q.{{SysApprovalRequest.entityId}} IN (SELECT CAST(e.{{FinJournal.journalId}} AS text) FROM entries e)
    GROUP BY q.{{SysApprovalRequest.entityId}}
)
SELECT
    e.{{FinJournal.journalId}}   AS journalId,
    e.{{FinJournal.journalNo}}   AS journalNo,
    e.{{FinJournal.postingDate}} AS postingDate,
    e.{{FinJournal.source}}      AS source,
    e.{{FinJournal.description}} AS description,
    e.{{FinJournal.totalDebit}}  AS amount,
    e.{{FinJournal.preparer}}    AS preparer,
    ev.outcome                   AS outcome,
    ev.versions                  AS ruleVersions,
    d.approver                   AS approver,
    d.approved_time              AS approvedTime,
    e.{{FinJournal.glNo}}        AS glNo
FROM entries e
LEFT JOIN decided d ON d.journal_ref = CAST(e.{{FinJournal.journalId}} AS text)
LEFT JOIN LATERAL (
    SELECT v.{{ApprovalEvaluation.outcome}} AS outcome, v.{{ApprovalEvaluation.ruleVersions}} AS versions
    FROM {{ApprovalEvaluation}} v
    WHERE v.{{ApprovalEvaluation.subject}} = 'fin.journal'
      AND v.{{ApprovalEvaluation.entityId}} = CAST(e.{{FinJournal.journalId}} AS text)
    ORDER BY v.{{ApprovalEvaluation.evaluatedTime}} DESC, v.{{ApprovalEvaluation.evaluationId}} DESC
    LIMIT 1
) ev ON TRUE
