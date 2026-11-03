/*---
id: qb.system.fee-invoice-transactions
description: The ledger transactions an invoice voided reverses (Q87, D-Q87-6) - its charges' postings (a prepaid charge's own payment among them) and a postpaid payment's, none reversed already
entities: [QbFeeInvoice, QbFeeCharge, LedgerTransaction]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
params:
  invoiceId: { like: QbFeeInvoice.invoiceId, required: true, description: The invoice ("none" for no invoice) }
  sponsorId: { like: QbFeeInvoice.sponsorId, required: true, description: The invoice's sponsor }
  period:    { like: QbFeeInvoice.period, required: true, description: The invoice's month }
results:
  transactionId: { from: LedgerTransaction.transactionId }
list:
  sorts: [transactionId]
  defaultSort: { field: transactionId, asc: true }
  key: [transactionId]
permissions: [qb.fee.system]
---*/
-- A prepaid invoice's charge is the one it names; a postpaid month's are the sponsor's postpaid charges of the month.
-- The postings whose source is one of them, and the invoice's own payment, that no reversal names yet.
WITH charges AS (
    SELECT c.{{QbFeeCharge.chargeId}} AS charge_id
    FROM {{QbFeeInvoice}} i
    JOIN {{QbFeeCharge}} c ON (i.{{QbFeeInvoice.chargeId}} IS NOT NULL
                                   AND CAST(c.{{QbFeeCharge.chargeId}} AS text) = i.{{QbFeeInvoice.chargeId}})
                           OR (i.{{QbFeeInvoice.chargeId}} IS NULL
                                   AND c.{{QbFeeCharge.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
                                   AND c.{{QbFeeCharge.period}} = i.{{QbFeeInvoice.period}}
                                   AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID')
    WHERE CAST(i.{{QbFeeInvoice.invoiceId}} AS text) = :invoiceId
      AND i.{{QbFeeInvoice.sponsorId}} = :sponsorId
      AND i.{{QbFeeInvoice.period}} = :period)
SELECT t.{{LedgerTransaction.transactionId}} AS transactionId
FROM {{LedgerTransaction}} t
WHERE t.{{LedgerTransaction.reversesTransactionId}} IS NULL
  AND ((t.{{LedgerTransaction.sourceEntity}} = 'QbFeeCharge'
        AND t.{{LedgerTransaction.sourceId}} IN (SELECT CAST(charge_id AS text) FROM charges))
       OR (t.{{LedgerTransaction.sourceEntity}} = 'QbFeeInvoice' AND t.{{LedgerTransaction.sourceId}} = :invoiceId))
  AND NOT EXISTS (SELECT 1 FROM {{LedgerTransaction}} r
                  WHERE r.{{LedgerTransaction.reversesTransactionId}} = t.{{LedgerTransaction.transactionId}})
