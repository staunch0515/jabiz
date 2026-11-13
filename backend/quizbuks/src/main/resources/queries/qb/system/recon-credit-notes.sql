/*---
id: qb.system.recon-credit-notes
description: Whether the audience fees' refunds and the credit notes are consistent, by sponsor (F-M7) - the audience fees not decided, charged for a publication that never went on, and the credit notes' uses and limits against their fees and invoices (one of the four templates of the daily fee-account reconciliation, F-M14 G10)
entities: [QbFeeCreditNote, QbFeeCreditNoteUse, QbFeeInvoice, QbFeeCharge, QbPrepayment, QbPublicationCard]
datasets:
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
  QbFeeCreditNoteUse: urn:jabiz:dataset:default:QbFeeCreditNoteUse
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
params:
  at: { like: QbPublicationCard.endedAt, description: "The run's time: an ended publication's prepayment still open a while after it is a difference (none: not checked)" }
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
