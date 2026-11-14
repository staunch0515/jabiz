/*---
id: qb.admin.fee-credit-notes
description: "The credit notes (F-M7), newest first, with the sponsor, what each gave back and what is left of its credit - for finance"
entities: [QbFeeCreditNote, QbFeeCreditNoteUse, QbSponsor]
datasets:
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
  QbFeeCreditNoteUse: urn:jabiz:dataset:default:QbFeeCreditNoteUse
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
results:
  creditNoteId:   { from: QbFeeCreditNote.creditNoteId }
  creditNoteNo:   { from: QbFeeCreditNote.creditNoteNo }
  sponsorId:      { from: QbFeeCreditNote.sponsorId }
  company:        { from: QbSponsor.company }
  operatorId:     { from: QbFeeCreditNote.operatorId }
  chargeId:       { from: QbFeeCreditNote.chargeId }
  invoiceNo:      { from: QbFeeCreditNote.invoiceNo }
  publicationId:  { from: QbFeeCreditNote.publicationId }
  billingMode:    { from: QbFeeCreditNote.billingMode }
  reason:         { from: QbFeeCreditNote.reason }
  endReason:      { from: QbFeeCreditNote.endReason }
  usedDays:       { from: QbFeeCreditNote.usedDays }
  note:           { from: QbFeeCreditNote.note }
  amount:         { from: QbFeeCreditNote.amount }
  tax:            { from: QbFeeCreditNote.tax }
  total:          { from: QbFeeCreditNote.total }
  appliedOwn:     { from: QbFeeCreditNote.appliedOwn }
  refundedCash:   { from: QbFeeCreditNote.refundedCash }
  refundedCredit: { from: QbFeeCreditNote.refundedCredit }
  voidedCredit:   { from: QbFeeCreditNote.voidedCredit }
  used:           { kind: { type: monetary, currency: JPY, scale: 0 } }
  givenBack:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  creditLeft:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  issuedAt:       { from: QbFeeCreditNote.issuedAt }
  issuedBy:       { from: QbFeeCreditNote.issuedBy }
list:
  filters: [sponsorId, operatorId, chargeId, invoiceNo, creditNoteNo, reason, issuedAt]
  sorts:   [issuedAt, total]
  defaultSort: { field: issuedAt, asc: false }
  key: [creditNoteId]
permissions: [qb.admin.finance.read]
---*/
SELECT
    n.{{QbFeeCreditNote.creditNoteId}}   AS creditNoteId,
    n.{{QbFeeCreditNote.creditNoteNo}}   AS creditNoteNo,
    n.{{QbFeeCreditNote.sponsorId}}      AS sponsorId,
    s.{{QbSponsor.company}}              AS company,
    n.{{QbFeeCreditNote.operatorId}}     AS operatorId,
    n.{{QbFeeCreditNote.chargeId}}       AS chargeId,
    n.{{QbFeeCreditNote.invoiceNo}}      AS invoiceNo,
    n.{{QbFeeCreditNote.publicationId}}  AS publicationId,
    n.{{QbFeeCreditNote.billingMode}}    AS billingMode,
    n.{{QbFeeCreditNote.reason}}         AS reason,
    n.{{QbFeeCreditNote.endReason}}      AS endReason,
    n.{{QbFeeCreditNote.usedDays}}       AS usedDays,
    n.{{QbFeeCreditNote.note}}           AS note,
    n.{{QbFeeCreditNote.amount}}         AS amount,
    n.{{QbFeeCreditNote.tax}}            AS tax,
    n.{{QbFeeCreditNote.total}}          AS total,
    n.{{QbFeeCreditNote.appliedOwn}}     AS appliedOwn,
    n.{{QbFeeCreditNote.refundedCash}}   AS refundedCash,
    n.{{QbFeeCreditNote.refundedCredit}} AS refundedCredit,
    n.{{QbFeeCreditNote.voidedCredit}}   AS voidedCredit,
    coalesce(u.r_used, 0)                AS used,
    coalesce(u.r_back, 0)                AS givenBack,
    n.{{QbFeeCreditNote.total}} - n.{{QbFeeCreditNote.refundedCash}} - n.{{QbFeeCreditNote.refundedCredit}}
        - coalesce(u.r_used, 0) - coalesce(u.r_back, 0) AS creditLeft,
    n.{{QbFeeCreditNote.issuedAt}}       AS issuedAt,
    n.{{QbFeeCreditNote.issuedBy}}       AS issuedBy
FROM {{QbFeeCreditNote}} n
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = n.{{QbFeeCreditNote.sponsorId}}
-- used: what its credit paid of invoices; givenBack (F-M15c): what the wind-up of an erased sponsor's account gave
-- back of it to what paid its fee (a use naming its posting instead of an invoice).
LEFT JOIN (SELECT x.{{QbFeeCreditNoteUse.creditNoteId}} AS r_note,
                  sum(CASE WHEN x.{{QbFeeCreditNoteUse.invoiceId}} IS NOT NULL
                           THEN x.{{QbFeeCreditNoteUse.amount}} ELSE 0 END) AS r_used,
                  sum(CASE WHEN x.{{QbFeeCreditNoteUse.invoiceId}} IS NULL
                           THEN x.{{QbFeeCreditNoteUse.amount}} ELSE 0 END) AS r_back
           FROM {{QbFeeCreditNoteUse}} x GROUP BY x.{{QbFeeCreditNoteUse.creditNoteId}}) u
       ON u.r_note = CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text)
