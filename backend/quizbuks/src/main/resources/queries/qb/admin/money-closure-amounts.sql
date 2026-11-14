/*---
id: qb.admin.money-closure-amounts
description: "F-M15 b §4.1, §8: the amounts of one erased sponsor's wind-up, summed from the payment parts, the lots and the ledger (the closure keeps none) - what the granted credit and the cash paid after the erasure, what was voided, what is still owed and the cash left in the account"
entities: [QbSponsorClosure, QbFeePayment, QbFeeCreditGrant, QbFeeInvoice, QbCashLot, LedgerTransaction, LedgerEntry, LedgerAccount]
datasets:
  QbSponsorClosure: urn:jabiz:dataset:default:QbSponsorClosure
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbCashLot: urn:jabiz:dataset:default:QbCashLot
params:
  sponsorId: { like: QbSponsorClosure.sponsorId, required: true, description: The erased sponsor }
results:
  sponsorId:       { from: QbSponsorClosure.sponsorId }
  paidFromCredit:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  paidFromCash:    { kind: { type: monetary, currency: JPY, scale: 0 } }
  creditVoided:    { kind: { type: monetary, currency: JPY, scale: 0 } }
  owed:            { kind: { type: monetary, currency: JPY, scale: 0 } }
  cashLeft:        { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.admin.finance.read]
---*/
-- No row when the sponsor has no wind-up. Paid: the payment parts of invoices paid at or after the erasure (less what
-- a voided invoice gave back: its negative parts), by sub-account. Voided: 5140 credited for the sponsor's lots at or
-- after the erasure (the wind-up's voiding and any expiry since). Owed: what the unpaid invoices still owe, as
-- QbFees.owed. Cash left: what the cash lots have left (it stays in the account).
SELECT c.{{QbSponsorClosure.sponsorId}} AS sponsorId,
       coalesce((SELECT sum(p.{{QbFeePayment.amount}}) FROM {{QbFeePayment}} p
                 WHERE p.{{QbFeePayment.sponsorId}} = c.{{QbSponsorClosure.sponsorId}}
                   AND p.{{QbFeePayment.targetEntity}} = 'QbFeeInvoice'
                   AND p.{{QbFeePayment.subAccount}} = 'FEE_CREDIT'
                   AND p.{{QbFeePayment.paidAt}} >= c.{{QbSponsorClosure.erasedAt}}), 0) AS paidFromCredit,
       coalesce((SELECT sum(p.{{QbFeePayment.amount}}) FROM {{QbFeePayment}} p
                 WHERE p.{{QbFeePayment.sponsorId}} = c.{{QbSponsorClosure.sponsorId}}
                   AND p.{{QbFeePayment.targetEntity}} = 'QbFeeInvoice'
                   AND p.{{QbFeePayment.subAccount}} = 'CASH'
                   AND p.{{QbFeePayment.paidAt}} >= c.{{QbSponsorClosure.erasedAt}}), 0) AS paidFromCash,
       coalesce((SELECT sum(e.{{LedgerEntry.amount}})
                 FROM {{LedgerTransaction}} t
                 JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
                 JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
                 JOIN {{QbFeeCreditGrant}} g
                     ON CAST(g.{{QbFeeCreditGrant.grantId}} AS text) = t.{{LedgerTransaction.sourceId}}
                 WHERE t.{{LedgerTransaction.sourceEntity}} = 'QbFeeCreditGrant'
                   AND g.{{QbFeeCreditGrant.sponsorId}} = c.{{QbSponsorClosure.sponsorId}}
                   AND a.{{LedgerAccount.accountCode}} = '5140'
                   AND e.{{LedgerEntry.direction}} = 'CREDIT'
                   AND t.{{LedgerTransaction.bookingTime}} >= c.{{QbSponsorClosure.erasedAt}}), 0) AS creditVoided,
       coalesce((SELECT sum(i.{{QbFeeInvoice.total}} - i.{{QbFeeInvoice.creditApplied}} - i.{{QbFeeInvoice.paidAmount}})
                 FROM {{QbFeeInvoice}} i
                 WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbSponsorClosure.sponsorId}}
                   AND i.{{QbFeeInvoice.status}} IN ('OPEN', 'PARTIAL', 'OVERDUE')), 0) AS owed,
       coalesce((SELECT sum(l.{{QbCashLot.remaining}}) FROM {{QbCashLot}} l
                 WHERE l.{{QbCashLot.sponsorId}} = c.{{QbSponsorClosure.sponsorId}}), 0) AS cashLeft
FROM {{QbSponsorClosure}} c
WHERE c.{{QbSponsorClosure.sponsorId}} = :sponsorId
