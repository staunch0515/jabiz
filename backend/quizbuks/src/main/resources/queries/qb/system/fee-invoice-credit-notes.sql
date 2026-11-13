/*---
id: qb.system.fee-invoice-credit-notes
description: "Whether an invoice may still be voided (F-M7 §10.3-3): the credit notes on its fees, and what notes paid of it"
entities: [QbFeeInvoice, QbFeeCharge, QbFeeCreditNote]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
params:
  invoiceId: { kind: { type: text, maxLength: 36 }, required: true, description: The invoice ("none" for no invoice) }
results:
  notes:         { kind: { type: numeric, precision: 9, scale: 0 } }
  creditApplied: { from: QbFeeInvoice.creditApplied }
permissions: [qb.fee.system]
---*/
-- At most one row. An invoice's fees as the void reverses them (qb.system.fee-invoice-transactions): a prepaid
-- invoice's the one it names; a postpaid month's the sponsor's postpaid fees of the month not paid early.
SELECT (SELECT count(*)
        FROM {{QbFeeCharge}} c
        JOIN {{QbFeeCreditNote}} n ON n.{{QbFeeCreditNote.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
        WHERE (i.{{QbFeeInvoice.chargeId}} IS NOT NULL
                   AND CAST(c.{{QbFeeCharge.chargeId}} AS text) = i.{{QbFeeInvoice.chargeId}})
           OR (i.{{QbFeeInvoice.chargeId}} IS NULL
                   AND c.{{QbFeeCharge.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
                   AND c.{{QbFeeCharge.period}} = i.{{QbFeeInvoice.period}}
                   AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                   AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                                   WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))) AS notes,
       i.{{QbFeeInvoice.creditApplied}} AS creditApplied
FROM {{QbFeeInvoice}} i
WHERE CAST(i.{{QbFeeInvoice.invoiceId}} AS text) = :invoiceId
