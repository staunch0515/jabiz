/*---
id: finance.gl.detail
description: >-
  The general ledger detail (FIN-RP-008): every entry line posted in a date range, by general ledger number and line,
  with its account, debit or credit, memo and dimensions, the entry's source, document and description, and who
  prepared the document and who approved it; one account or one source if asked.
entities: [FinPosting, FinJournal, FinBill, FinInvoice, FinReceipt, FinPayment, FinPaymentRun, SysApprovalRequest,
  ApprovalDecision, LedgerTransaction, LedgerEntry, LedgerAccount]
params:
  from:        { like: FinPosting.postingDate, required: true, description: first posting date }
  to:          { like: FinPosting.postingDate, required: true, description: last posting date }
  accountCode: { like: LedgerAccount.accountCode, description: "only this account's lines" }
  source:      { like: FinPosting.source, description: "only entries from this source" }
results:
  glNo:         { from: FinPosting.glNo }
  lineNo:       { from: LedgerEntry.lineNo }
  postingDate:  { from: FinPosting.postingDate }
  source:       { from: FinPosting.source }
  documentNo:   { from: FinPosting.documentNo }
  description:  { from: LedgerTransaction.description }
  accountCode:  { from: LedgerAccount.accountCode }
  accountName:  { from: LedgerAccount.accountName }
  debit:        { from: LedgerEntry.amount }
  credit:       { from: LedgerEntry.amount }
  memo:         { from: LedgerEntry.memo }
  department:   { from: LedgerEntry.dimension1 }
  location:     { from: LedgerEntry.dimension2 }
  preparer:     { kind: { type: text, maxLength: 100 } }
  approver:     { kind: { type: text, maxLength: 500 } }
  sourceEntity: { from: FinPosting.sourceEntity }
  sourceId:     { from: FinPosting.sourceId }
list:
  filters: [accountCode, source, documentNo, glNo, preparer, approver, department, location]
  sorts:   [glNo, postingDate, accountCode, documentNo]
  defaultSort: { field: glNo, asc: true }
  key: [glNo, lineNo]
permissions: [ledger.read, fin.journal.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
-- Who prepared each posting's document and who approved it (FIN-RP-008): the preparer recorded on the journal entry,
-- bill, invoice, receipt or payment run, and the approvers of the document's approved request.
WITH who AS (
    SELECT fp.{{FinPosting.postingId}} AS posting_key,
           COALESCE(j.{{FinJournal.preparer}}, b.{{FinBill.preparedBy}}, i.{{FinInvoice.preparedBy}},
                    r.{{FinReceipt.preparedBy}}, pr.{{FinPaymentRun.preparedBy}}) AS doc_preparer,
           COALESCE(CAST(pr.{{FinPaymentRun.runId}} AS text), fp.{{FinPosting.sourceId}}) AS approval_key
    FROM {{FinPosting}} fp
    LEFT JOIN {{FinJournal}} j
      ON fp.{{FinPosting.sourceEntity}} = 'FinJournal'
     AND CAST(j.{{FinJournal.journalId}} AS text) = fp.{{FinPosting.sourceId}}
    LEFT JOIN {{FinBill}} b
      ON fp.{{FinPosting.sourceEntity}} = 'FinBill'
     AND CAST(b.{{FinBill.billId}} AS text) = fp.{{FinPosting.sourceId}}
    LEFT JOIN {{FinInvoice}} i
      ON fp.{{FinPosting.sourceEntity}} = 'FinInvoice'
     AND CAST(i.{{FinInvoice.invoiceId}} AS text) = fp.{{FinPosting.sourceId}}
    LEFT JOIN {{FinReceipt}} r
      ON fp.{{FinPosting.sourceEntity}} = 'FinReceipt'
     AND CAST(r.{{FinReceipt.receiptId}} AS text) = fp.{{FinPosting.sourceId}}
    LEFT JOIN {{FinPayment}} pm
      ON fp.{{FinPosting.sourceEntity}} = 'FinPayment'
     AND CAST(pm.{{FinPayment.paymentId}} AS text) = fp.{{FinPosting.sourceId}}
    LEFT JOIN {{FinPaymentRun}} pr ON pr.{{FinPaymentRun.runId}} = pm.{{FinPayment.runId}}
    WHERE fp.{{FinPosting.postingDate}} BETWEEN :from AND :to
),
approvers AS (
    SELECT w.posting_key,
           STRING_AGG(DISTINCT d.{{ApprovalDecision.approverId}}, ', ') AS approver_ids
    FROM who w
    JOIN {{SysApprovalRequest}} q ON q.{{SysApprovalRequest.entityId}} = w.approval_key
    JOIN {{ApprovalDecision}} d ON d.{{ApprovalDecision.requestId}} = q.{{SysApprovalRequest.requestId}}
    WHERE q.{{SysApprovalRequest.status}} = 'APPROVED' AND d.{{ApprovalDecision.decision}} = 'APPROVE'
    GROUP BY w.posting_key
)
SELECT fp.{{FinPosting.glNo}} AS glNo, e.{{LedgerEntry.lineNo}} AS lineNo,
       fp.{{FinPosting.postingDate}} AS postingDate, fp.{{FinPosting.source}} AS source,
       fp.{{FinPosting.documentNo}} AS documentNo, t.{{LedgerTransaction.description}} AS description,
       a.{{LedgerAccount.accountCode}} AS accountCode, a.{{LedgerAccount.accountName}} AS accountName,
       CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END AS debit,
       CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END AS credit,
       e.{{LedgerEntry.memo}} AS memo, e.{{LedgerEntry.dimension1}} AS department,
       e.{{LedgerEntry.dimension2}} AS location, w.doc_preparer AS preparer, ap.approver_ids AS approver,
       fp.{{FinPosting.sourceEntity}} AS sourceEntity, fp.{{FinPosting.sourceId}} AS sourceId
FROM {{FinPosting}} fp
JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = fp.{{FinPosting.transactionId}}
JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = fp.{{FinPosting.transactionId}}
JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
JOIN who w ON w.posting_key = fp.{{FinPosting.postingId}}
LEFT JOIN approvers ap ON ap.posting_key = fp.{{FinPosting.postingId}}
WHERE fp.{{FinPosting.postingDate}} BETWEEN :from AND :to
  AND (CAST(:accountCode AS text) IS NULL OR a.{{LedgerAccount.accountCode}} = :accountCode)
  AND (CAST(:source AS text) IS NULL OR fp.{{FinPosting.source}} = :source)
