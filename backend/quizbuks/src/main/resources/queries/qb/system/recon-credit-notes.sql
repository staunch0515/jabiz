/*---
id: qb.system.recon-credit-notes
description: Whether the audience fees' refunds and the credit notes are consistent, by sponsor (F-M7) - the audience fees not decided, charged for a publication that never went on, and the credit notes' uses and limits against their fees and invoices (one of the four templates of the daily fee-account reconciliation, F-M14 G10)
entities: [QbFeeCreditNote, QbFeeCreditNoteUse, QbFeeInvoice, QbFeePayment, QbFeeCreditGrant, QbWallet, SysParam, QbFeeCharge, QbPrepayment, QbPublicationCard]
datasets:
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
  QbFeeCreditNoteUse: urn:jabiz:dataset:default:QbFeeCreditNoteUse
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbWallet: urn:jabiz:dataset:default:QbWallet
  SysParam: urn:jabiz:dataset:now:SysParam
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
params:
  at: { like: QbPublicationCard.endedAt, description: "The run's time: an ended publication's prepayment still open a while after it is a difference (none: not checked)" }
  missedHours: { kind: { type: numeric, precision: 3, scale: 0 }, description: "F-M13c: qb.fee.auto-pay.missed-after-hours (none: the automatic payment's misses are not checked)" }
  refundHours: { kind: { type: numeric, precision: 3, scale: 0 }, description: "F-M7: qb.audience.refund.missing-after-hours (none: the audience fees not decided are not checked)" }
results:
  checkCode: { kind: { type: text, maxLength: 30 } }
  sponsorId: { kind: { type: text, maxLength: 128 } }
  reference: { kind: { type: text, maxLength: 128 } }
  expected:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  actual:    { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [checkCode, sponsorId]
  defaultSort: { field: checkCode, asc: true }
  key: [checkCode, sponsorId, reference]
permissions: [qb.wallet.system]
---*/
-- AUDIENCE_REFUND_MISSING (F-M7, N9): by sponsor, the audience fees charged whose publication ended more than
--   qb.audience.refund.missing-after-hours (handed in) before :at and whose unused days are not decided: expected 0,
--   actual how many. AUDIENCE_REFUND_NO_PERIOD: by prepayment (the reference), one decided NONE_NO_PERIOD (no period to
--   count the days by) that finance has not decided on yet: expected 0, actual 1. Cleared by a note by hand on its fee,
--   or by finance closing it with no note (QB_AUDIENCE_REFUND_CLOSE: NONE_FINANCE). AUDIENCE_FEE_NOT_LIVE: an audience fee whose
--   publication never went on (no card): expected 0, actual the fees.
-- CREDIT_NOTE_USES (F-M7): by sponsor and note (the reference), what a note gave back and what its credit paid against
--   its total: expected the total, actual those (a difference only above it). CREDIT_NOTE_LIMITS: by sponsor and fee,
--   its notes' amounts and taxes against the fee's: expected the fee's amount and tax together, actual the notes'
--   (only when either is above). CREDIT_NOTE_USE_VOID: a use on a voided invoice: expected 0, actual its amount.
--   CREDIT_UNUSED_WITH_DUE: a sponsor with credit left and an invoice unpaid at once: expected 0, actual the credit.
SELECT CAST('AUDIENCE_REFUND_MISSING' AS varchar(30)) AS checkCode,
       CAST(ap.{{QbPrepayment.sponsorId}} AS varchar(128)) AS sponsorId,
       CAST(NULL AS varchar(128)) AS reference, CAST(0 AS numeric) AS expected, CAST(count(*) AS numeric) AS actual
FROM {{QbPrepayment}} ap
JOIN {{QbPublicationCard}} ac ON CAST(ac.{{QbPublicationCard.publicationId}} AS text) = ap.{{QbPrepayment.publicationId}}
WHERE ap.{{QbPrepayment.purpose}} = 'AUDIENCE'
  AND ap.{{QbPrepayment.chargedAt}} IS NOT NULL
  AND ap.{{QbPrepayment.refundDecidedAt}} IS NULL
  AND ac.{{QbPublicationCard.status}} = 'ENDED'
  AND CAST(:at AS timestamptz) IS NOT NULL AND CAST(:refundHours AS int) IS NOT NULL
  AND ac.{{QbPublicationCard.endedAt}} < CAST(:at AS timestamptz) - make_interval(hours => CAST(:refundHours AS int))
GROUP BY ap.{{QbPrepayment.sponsorId}}
UNION ALL
SELECT CAST('AUDIENCE_REFUND_NO_PERIOD' AS varchar(30)), CAST(ap.{{QbPrepayment.sponsorId}} AS varchar(128)),
       CAST(ap.{{QbPrepayment.prepaymentId}} AS varchar(128)), CAST(0 AS numeric), CAST(1 AS numeric)
FROM {{QbPrepayment}} ap
WHERE ap.{{QbPrepayment.refundOutcome}} = 'NONE_NO_PERIOD'
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeCharge}} nc
                  JOIN {{QbFeeCreditNote}} nn ON nn.{{QbFeeCreditNote.chargeId}} = CAST(nc.{{QbFeeCharge.chargeId}} AS text)
                  WHERE nc.{{QbFeeCharge.sponsorId}} = ap.{{QbPrepayment.sponsorId}}
                    AND nc.{{QbFeeCharge.kind}} = 'AUDIENCE'
                    AND nc.{{QbFeeCharge.sourceKey}} = 'publication:' || CAST(ap.{{QbPrepayment.publicationId}} AS text))
UNION ALL
SELECT CAST('AUDIENCE_FEE_NOT_LIVE' AS varchar(30)), CAST(ch.{{QbFeeCharge.sponsorId}} AS varchar(128)),
       CAST(NULL AS varchar(128)), CAST(0 AS numeric), sum(ch.{{QbFeeCharge.amount}})
FROM {{QbFeeCharge}} ch
WHERE ch.{{QbFeeCharge.kind}} = 'AUDIENCE'
  AND NOT EXISTS (SELECT 1 FROM {{QbPublicationCard}} cc
                  WHERE 'publication:' || CAST(cc.{{QbPublicationCard.publicationId}} AS text) = ch.{{QbFeeCharge.sourceKey}})
GROUP BY ch.{{QbFeeCharge.sponsorId}}
UNION ALL
SELECT CAST('CREDIT_NOTE_USES' AS varchar(30)), CAST(n.{{QbFeeCreditNote.sponsorId}} AS varchar(128)),
       CAST(n.{{QbFeeCreditNote.creditNoteNo}} AS varchar(128)), n.{{QbFeeCreditNote.total}},
       n.{{QbFeeCreditNote.refundedCash}} + n.{{QbFeeCreditNote.refundedCredit}} + coalesce(nu.r_used, 0)
FROM {{QbFeeCreditNote}} n
LEFT JOIN (SELECT u.{{QbFeeCreditNoteUse.creditNoteId}} AS r_note, sum(u.{{QbFeeCreditNoteUse.amount}}) AS r_used
           FROM {{QbFeeCreditNoteUse}} u GROUP BY u.{{QbFeeCreditNoteUse.creditNoteId}}) nu
       ON nu.r_note = CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text)
WHERE n.{{QbFeeCreditNote.refundedCash}} + n.{{QbFeeCreditNote.refundedCredit}} + coalesce(nu.r_used, 0)
      > n.{{QbFeeCreditNote.total}}
UNION ALL
SELECT CAST('CREDIT_NOTE_LIMITS' AS varchar(30)), CAST(ch.{{QbFeeCharge.sponsorId}} AS varchar(128)),
       CAST(CAST(ch.{{QbFeeCharge.chargeId}} AS text) AS varchar(128)),
       ch.{{QbFeeCharge.amount}} + ch.{{QbFeeCharge.tax}}, nl.r_amount + nl.r_tax
FROM {{QbFeeCharge}} ch
JOIN (SELECT n.{{QbFeeCreditNote.chargeId}} AS r_charge, sum(n.{{QbFeeCreditNote.amount}}) AS r_amount,
             sum(n.{{QbFeeCreditNote.tax}}) AS r_tax
      FROM {{QbFeeCreditNote}} n GROUP BY n.{{QbFeeCreditNote.chargeId}}) nl
     ON nl.r_charge = CAST(ch.{{QbFeeCharge.chargeId}} AS text)
WHERE nl.r_amount > ch.{{QbFeeCharge.amount}} OR nl.r_tax > ch.{{QbFeeCharge.tax}}
UNION ALL
SELECT CAST('CREDIT_NOTE_USE_VOID' AS varchar(30)), CAST(u.{{QbFeeCreditNoteUse.sponsorId}} AS varchar(128)),
       CAST(vi.{{QbFeeInvoice.invoiceNo}} AS varchar(128)), CAST(0 AS numeric), sum(u.{{QbFeeCreditNoteUse.amount}})
FROM {{QbFeeCreditNoteUse}} u
JOIN {{QbFeeInvoice}} vi ON CAST(vi.{{QbFeeInvoice.invoiceId}} AS text) = u.{{QbFeeCreditNoteUse.invoiceId}}
WHERE vi.{{QbFeeInvoice.status}} = 'VOID'
GROUP BY u.{{QbFeeCreditNoteUse.sponsorId}}, vi.{{QbFeeInvoice.invoiceNo}}
UNION ALL
SELECT CAST('CREDIT_UNUSED_WITH_DUE' AS varchar(30)), CAST(cl.r_party AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), cl.r_left
FROM (SELECT n.{{QbFeeCreditNote.sponsorId}} AS r_party,
             sum(n.{{QbFeeCreditNote.total}} - n.{{QbFeeCreditNote.refundedCash}} - n.{{QbFeeCreditNote.refundedCredit}})
             - coalesce((SELECT sum(u.{{QbFeeCreditNoteUse.amount}}) FROM {{QbFeeCreditNoteUse}} u
                         WHERE u.{{QbFeeCreditNoteUse.sponsorId}} = n.{{QbFeeCreditNote.sponsorId}}), 0) AS r_left
      FROM {{QbFeeCreditNote}} n
      GROUP BY n.{{QbFeeCreditNote.sponsorId}}) cl
WHERE cl.r_left > 0
  AND EXISTS (SELECT 1 FROM {{QbFeeInvoice}} ui
              WHERE ui.{{QbFeeInvoice.sponsorId}} = cl.r_party
                AND ui.{{QbFeeInvoice.status}} IN ('OPEN', 'PARTIAL', 'OVERDUE'))
UNION ALL
-- INVOICE_PAID_SUM (F-M13c, a difference): by invoice (not voided), what the invoice says was paid (paidAmount) against
--   what its payment parts add up to (a part given back is negative; an invoice of one charge was paid as the charge), and its credit applied against what its notes'
--   uses add up to. An invoice paid before the parts existed (paidAmount, no part) is left out; so is one with no
--   credit applied and no use.
SELECT CAST('INVOICE_PAID_SUM' AS varchar(30)), CAST(pi.{{QbFeeInvoice.sponsorId}} AS varchar(128)),
       CAST(pi.{{QbFeeInvoice.invoiceNo}} AS varchar(128)),
       pi.{{QbFeeInvoice.paidAmount}} + pi.{{QbFeeInvoice.creditApplied}},
       coalesce(pp.r_paid, pi.{{QbFeeInvoice.paidAmount}}) + coalesce(pu.r_used, 0)
FROM {{QbFeeInvoice}} pi
LEFT JOIN LATERAL (SELECT sum(p.{{QbFeePayment.amount}}) AS r_paid
           FROM {{QbFeePayment}} p
           WHERE (p.{{QbFeePayment.targetEntity}} = 'QbFeeInvoice'
                  AND p.{{QbFeePayment.targetId}} = CAST(pi.{{QbFeeInvoice.invoiceId}} AS text))
              OR (p.{{QbFeePayment.targetEntity}} = 'QbFeeCharge'
                  AND p.{{QbFeePayment.targetId}} = pi.{{QbFeeInvoice.chargeId}})) pp ON true
LEFT JOIN (SELECT u.{{QbFeeCreditNoteUse.invoiceId}} AS r_invoice, sum(u.{{QbFeeCreditNoteUse.amount}}) AS r_used
           FROM {{QbFeeCreditNoteUse}} u GROUP BY u.{{QbFeeCreditNoteUse.invoiceId}}) pu
       ON pu.r_invoice = CAST(pi.{{QbFeeInvoice.invoiceId}} AS text)
WHERE pi.{{QbFeeInvoice.status}} <> 'VOID'
  AND pi.{{QbFeeInvoice.paidAmount}} + pi.{{QbFeeInvoice.creditApplied}}
      <> coalesce(pp.r_paid, pi.{{QbFeeInvoice.paidAmount}}) + coalesce(pu.r_used, 0)
UNION ALL
-- AUTO_PAY_MISSED (F-M13c, for attention): by sponsor, with the automatic payment on (the parameter is true, read as the
--   run reads it), the unpaid invoices issued more than qb.fee.auto-pay.missed-after-hours (handed in) before :at while
--   the sponsor's account has something to pay them with (the fee credit not expired plus the cash not held): expected
--   0, actual the invoices' count. The nightly run pays them, so one still unpaid after the hours is a payment that did
--   not run or did not finish; it clears itself when they are paid.
SELECT CAST('AUTO_PAY_MISSED' AS varchar(30)), CAST(mi.{{QbFeeInvoice.sponsorId}} AS varchar(128)),
       CAST(NULL AS varchar(128)), CAST(0 AS numeric), CAST(count(*) AS numeric)
FROM {{QbFeeInvoice}} mi
WHERE CAST(:at AS timestamptz) IS NOT NULL AND CAST(:missedHours AS int) IS NOT NULL
  AND (SELECT max(p.{{SysParam.value}}) FROM {{SysParam}} p WHERE p.{{SysParam.paramKey}} = 'qb.fee.auto-pay.enabled') = 'true'
  AND mi.{{QbFeeInvoice.status}} IN ('OPEN', 'PARTIAL', 'OVERDUE')
  AND mi.{{QbFeeInvoice.total}} - mi.{{QbFeeInvoice.paidAmount}} - mi.{{QbFeeInvoice.creditApplied}} > 0
  AND mi.{{QbFeeInvoice.issuedAt}} < CAST(:at AS timestamptz) - make_interval(hours => CAST(:missedHours AS int))
  AND coalesce((SELECT sum(g.{{QbFeeCreditGrant.remaining}}) FROM {{QbFeeCreditGrant}} g
                WHERE g.{{QbFeeCreditGrant.sponsorId}} = mi.{{QbFeeInvoice.sponsorId}}
                  AND g.{{QbFeeCreditGrant.remaining}} > 0 AND g.{{QbFeeCreditGrant.expiresAt}} > CAST(:at AS timestamptz)), 0)
      + coalesce((SELECT sum(greatest(w.{{QbWallet.balance}} - w.{{QbWallet.held}}, 0)) FROM {{QbWallet}} w
                  WHERE w.{{QbWallet.ownerId}} = mi.{{QbFeeInvoice.sponsorId}} AND w.{{QbWallet.kind}} = 'CASH'), 0) > 0
GROUP BY mi.{{QbFeeInvoice.sponsorId}}
