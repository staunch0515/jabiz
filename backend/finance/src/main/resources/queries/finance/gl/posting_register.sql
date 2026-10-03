/*---
id: finance.gl.posting_register
description: >-
  The general ledger's register (FIN-RP-008): every entry posted in a date range, in general ledger number order, with
  its source, document, description, amount (its debits), who prepared the document and who approved it. A document
  without an approval shows no approver; a payment shows its payment run's preparer and approvers.
entities: [FinPosting, FinJournal, FinBill, FinInvoice, FinReceipt, FinPayment, FinPaymentRun, SysApprovalRequest,
  ApprovalDecision, LedgerTransaction, LedgerEntry, LedgerAccount]
params:
  from: { like: FinPosting.postingDate, required: true, description: first posting date }
  to:   { like: FinPosting.postingDate, required: true, description: last posting date }
results:
  glNo:         { from: FinPosting.glNo }
  postingDate:  { from: FinPosting.postingDate }
  periodKey:    { from: FinPosting.periodKey }
  source:       { from: FinPosting.source }
  documentNo:   { from: FinPosting.documentNo }
  description:  { from: LedgerTransaction.description }
  amount:       { from: LedgerEntry.amount }
  preparer:     { kind: { type: text, maxLength: 100 } }
  approver:     { kind: { type: text, maxLength: 500 } }
  sourceEntity: { from: FinPosting.sourceEntity }
  sourceId:     { from: FinPosting.sourceId }
list:
  filters: [source, documentNo, periodKey, preparer, approver, glNo]
  sorts:   [glNo, postingDate, documentNo, amount]
  defaultSort: { field: glNo, asc: true }
  key: [glNo]
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
),
amounts AS (
    SELECT e.{{LedgerEntry.transactionId}} AS txn,
           SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END) AS total_dr
    FROM {{LedgerEntry}} e
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE fp.{{FinPosting.postingDate}} BETWEEN :from AND :to
    GROUP BY e.{{LedgerEntry.transactionId}}
)
SELECT fp.{{FinPosting.glNo}} AS glNo, fp.{{FinPosting.postingDate}} AS postingDate,
       fp.{{FinPosting.periodKey}} AS periodKey, fp.{{FinPosting.source}} AS source,
       fp.{{FinPosting.documentNo}} AS documentNo, t.{{LedgerTransaction.description}} AS description,
       am.total_dr AS amount, w.doc_preparer AS preparer, ap.approver_ids AS approver,
       fp.{{FinPosting.sourceEntity}} AS sourceEntity, fp.{{FinPosting.sourceId}} AS sourceId
FROM {{FinPosting}} fp
JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = fp.{{FinPosting.transactionId}}
JOIN amounts am ON am.txn = fp.{{FinPosting.transactionId}}
JOIN who w ON w.posting_key = fp.{{FinPosting.postingId}}
LEFT JOIN approvers ap ON ap.posting_key = fp.{{FinPosting.postingId}}
WHERE fp.{{FinPosting.postingDate}} BETWEEN :from AND :to
