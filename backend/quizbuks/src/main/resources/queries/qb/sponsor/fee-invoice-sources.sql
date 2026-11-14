/*---
id: qb.sponsor.fee-invoice-sources
description: "F-M13: what an invoice of the sponsor's own has been paid with, in one row - the gift credit, the prepayment (and the part of it that was gift credit), the cash, the credit notes, and what is still owed - the document's 'payment status' and the page's 'payment details'"
entities: [QbFeeInvoice, QbFeePayment]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
  QbFeePayment: urn:jabiz:dataset:sponsor:QbFeePayment
params:
  invoiceId: { like: QbFeeInvoice.invoiceId, required: true, description: The invoice }
  asOfDay:   { kind: { type: date }, description: "The day the document states the payment status as of (the issue's day in the fiscal zone); none: not stated" }
results:
  asOf:                { kind: { type: date } }
  sourceGift:          { kind: { type: monetary, currency: JPY, scale: 0 } }
  sourcePrepayment:    { kind: { type: monetary, currency: JPY, scale: 0 } }
  sourcePrepaymentGift: { kind: { type: monetary, currency: JPY, scale: 0 } }
  sourceCash:          { kind: { type: monetary, currency: JPY, scale: 0 } }
  sourceCreditNote:    { kind: { type: monetary, currency: JPY, scale: 0 } }
  sourceUnpaid:        { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.topup]
---*/
-- THE definition of an invoice's payment sources; qb.admin.fee-invoice-sources is the same statement over the
-- administration's datasets: CHANGE BOTH (InvoiceSourcesTemplateIT compares them). One statement, one snapshot: the
-- payment rows, the credit notes' share and the total are read together, so the five add up to the total whatever is
-- being paid just now (the identity: gift + prepayment + cash + credit notes + unpaid = total; 'prepayment gift' is the
-- part of the prepayment's rows that came from gift credit and is not added).
-- The payment rows of an invoice: the ones that name it, or the charge it is the own invoice of (a prepaid charge's
-- invoice, a fee paid early); a postpaid month's invoice is paid by rows that name it. A row with a prepayment is the
-- prepayment's; else a gift-credit sub-account's is the gift credit's; else cash. Voided: what was paid was given back
-- (the negative rows), so nothing is owed.
WITH paid AS (
    SELECT i.{{QbFeeInvoice.invoiceId}} AS invoice_id,
           sum(CASE WHEN p.{{QbFeePayment.prepaymentId}} IS NULL AND p.{{QbFeePayment.subAccount}} = 'FEE_CREDIT'
                    THEN p.{{QbFeePayment.amount}} ELSE 0 END) AS gift,
           sum(CASE WHEN p.{{QbFeePayment.prepaymentId}} IS NOT NULL THEN p.{{QbFeePayment.amount}} ELSE 0 END) AS prepayment,
           sum(CASE WHEN p.{{QbFeePayment.prepaymentId}} IS NOT NULL AND p.{{QbFeePayment.subAccount}} = 'FEE_CREDIT'
                    THEN p.{{QbFeePayment.amount}} ELSE 0 END) AS prepayment_gift,
           sum(CASE WHEN p.{{QbFeePayment.prepaymentId}} IS NULL AND p.{{QbFeePayment.subAccount}} <> 'FEE_CREDIT'
                    THEN p.{{QbFeePayment.amount}} ELSE 0 END) AS cash
    FROM {{QbFeeInvoice}} i
    JOIN {{QbFeePayment}} p
      ON (p.{{QbFeePayment.targetEntity}} = 'QbFeeInvoice'
          AND p.{{QbFeePayment.targetId}} = CAST(i.{{QbFeeInvoice.invoiceId}} AS text))
      OR (p.{{QbFeePayment.targetEntity}} = 'QbFeeCharge'
          AND i.{{QbFeeInvoice.chargeId}} IS NOT NULL AND p.{{QbFeePayment.targetId}} = i.{{QbFeeInvoice.chargeId}})
    WHERE i.{{QbFeeInvoice.invoiceId}} = :invoiceId
    GROUP BY i.{{QbFeeInvoice.invoiceId}}
)
SELECT CAST(:asOfDay AS date) AS asOf,
       coalesce(x.gift, 0) AS sourceGift,
       coalesce(x.prepayment, 0) AS sourcePrepayment,
       coalesce(x.prepayment_gift, 0) AS sourcePrepaymentGift,
       coalesce(x.cash, 0) AS sourceCash,
       i.{{QbFeeInvoice.creditApplied}} AS sourceCreditNote,
       CASE WHEN i.{{QbFeeInvoice.status}} = 'VOID' THEN 0
            ELSE i.{{QbFeeInvoice.total}} - i.{{QbFeeInvoice.creditApplied}}
                 - coalesce(x.gift, 0) - coalesce(x.prepayment, 0) - coalesce(x.cash, 0) END AS sourceUnpaid
FROM {{QbFeeInvoice}} i
LEFT JOIN paid x ON x.invoice_id = i.{{QbFeeInvoice.invoiceId}}
WHERE i.{{QbFeeInvoice.invoiceId}} = :invoiceId
