/*---
id: qb.system.fee-void-transactions
description: "Q87b: the postings an invoice's voiding reverses - its month's charges, its adjustment, the month's payments - with what each took from the cash account"
entities: [QbFeeInvoice, QbFeeCharge, QbFeePayment, LedgerTransaction, LedgerEntry, LedgerAccount]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
params:
  invoiceId: { like: QbFeeInvoice.invoiceId, required: true, description: The invoice }
results:
  transactionId: { from: LedgerTransaction.transactionId }
  cash:          { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [transactionId]
  defaultSort: { field: transactionId, asc: true }
  key: [transactionId]
permissions: [qb.fee.system]
---*/
-- cash: what the posting took from the sponsor's cash account (2160 debits less credits): its reversal gives it back.
-- Postings already reversed are not reversed again.
WITH inv AS (
    SELECT i.{{QbFeeInvoice.invoiceId}} AS inv_key, i.{{QbFeeInvoice.sponsorId}} AS inv_sponsor,
           i.{{QbFeeInvoice.period}} AS inv_month
    FROM {{QbFeeInvoice}} i WHERE i.{{QbFeeInvoice.invoiceId}} = :invoiceId
), sources AS (
    SELECT 'QbFeeInvoice' AS entity, CAST(inv.inv_key AS text) AS id FROM inv
    UNION ALL
    SELECT 'QbFeeCharge', CAST(c.{{QbFeeCharge.chargeId}} AS text) FROM {{QbFeeCharge}} c JOIN inv
        ON c.{{QbFeeCharge.sponsorId}} = inv.inv_sponsor AND c.{{QbFeeCharge.period}} = inv.inv_month
    UNION ALL
    SELECT 'QbFeePayment', CAST(p.{{QbFeePayment.paymentId}} AS text) FROM {{QbFeePayment}} p JOIN inv
        ON p.{{QbFeePayment.sponsorId}} = inv.inv_sponsor AND p.{{QbFeePayment.period}} = inv.inv_month
)
SELECT t.{{LedgerTransaction.transactionId}} AS transactionId,
       coalesce((SELECT sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                 ELSE -e.{{LedgerEntry.amount}} END)
            FROM {{LedgerEntry}} e
            JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
            WHERE e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
              AND a.{{LedgerAccount.accountCode}} = '2160'), 0) AS cash
FROM {{LedgerTransaction}} t
JOIN sources s ON s.entity = t.{{LedgerTransaction.sourceEntity}} AND s.id = t.{{LedgerTransaction.sourceId}}
WHERE t.{{LedgerTransaction.reversesTransactionId}} IS NULL
  AND NOT EXISTS (SELECT 1 FROM {{LedgerTransaction}} r
      WHERE r.{{LedgerTransaction.reversesTransactionId}} = t.{{LedgerTransaction.transactionId}})
