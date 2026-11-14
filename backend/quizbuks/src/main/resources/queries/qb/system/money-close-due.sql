/*---
id: qb.system.money-close-due
description: "The erased sponsors whose platform fee account is to be wound up (the items of the daily job qb.sponsor-money-close, F-M15 b §5.6): not wound up yet, wound up with fees owed and cash that arrived since, wound up with a postpaid fee charged since and not invoiced, or (F-M15c) wound up with a credit note's credit left since"
entities: [QbSponsorClosure, QbWallet, QbFeeCharge, QbFeeInvoice, QbFeeCreditNote, QbFeeCreditNoteUse]
datasets:
  QbSponsorClosure: urn:jabiz:dataset:default:QbSponsorClosure
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
  QbFeeCreditNoteUse: urn:jabiz:dataset:default:QbFeeCreditNoteUse
results:
  sponsorId: { from: QbSponsorClosure.sponsorId }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.fee.system]
---*/
-- Wound up with fees owed (M8): cash that arrived after it (a top-up confirmed late, an invoice voided) is in the cash
-- wallet, less what a dispute holds (review L2: what the payment can spend), and pays again.
-- Wound up, then a postpaid fee charged (Fable's re-review: it goes on a month not invoiced, FeeRules.chargePeriod):
-- the next run invoices it. The condition is qb.system.money-close-uninvoiced's (change both).
-- Wound up, then a credit note issued with credit left (F-M15c: an audience fee's refund decided after the erasure):
-- the next run pays the invoices with it and gives the rest back to the cash. The credit left is
-- qb.system.credit-notes-left's (change both).
-- The item reads everything again under the sponsor's wallet lock.
SELECT c.{{QbSponsorClosure.sponsorId}} AS sponsorId
FROM {{QbSponsorClosure}} c
WHERE c.{{QbSponsorClosure.status}} = 'WAITING'
   OR (c.{{QbSponsorClosure.status}} = 'CLOSED'
       AND c.{{QbSponsorClosure.reasonCode}} = 'FEE_OWED'
       AND EXISTS (SELECT 1 FROM {{QbWallet}} w
                   WHERE w.{{QbWallet.ownerId}} = c.{{QbSponsorClosure.sponsorId}}
                     AND w.{{QbWallet.kind}} = 'CASH'
                     AND w.{{QbWallet.balance}} - w.{{QbWallet.held}} > 0))
   OR (c.{{QbSponsorClosure.status}} = 'CLOSED'
       AND EXISTS (SELECT 1 FROM {{QbFeeCharge}} f
                   WHERE f.{{QbFeeCharge.sponsorId}} = c.{{QbSponsorClosure.sponsorId}}
                     AND f.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                     AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                                     WHERE i.{{QbFeeInvoice.sponsorId}} = f.{{QbFeeCharge.sponsorId}}
                                       AND ((i.{{QbFeeInvoice.period}} = f.{{QbFeeCharge.period}}
                                             AND i.{{QbFeeInvoice.chargeId}} IS NULL)
                                            OR i.{{QbFeeInvoice.chargeId}} = CAST(f.{{QbFeeCharge.chargeId}} AS text)))))
   OR (c.{{QbSponsorClosure.status}} = 'CLOSED'
       AND EXISTS (SELECT 1 FROM {{QbFeeCreditNote}} n
                   WHERE n.{{QbFeeCreditNote.sponsorId}} = c.{{QbSponsorClosure.sponsorId}}
                     AND n.{{QbFeeCreditNote.total}} - n.{{QbFeeCreditNote.refundedCash}}
                         - n.{{QbFeeCreditNote.refundedCredit}}
                         - coalesce((SELECT sum(u.{{QbFeeCreditNoteUse.amount}}) FROM {{QbFeeCreditNoteUse}} u
                                     WHERE u.{{QbFeeCreditNoteUse.creditNoteId}}
                                           = CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text)), 0) > 0))
