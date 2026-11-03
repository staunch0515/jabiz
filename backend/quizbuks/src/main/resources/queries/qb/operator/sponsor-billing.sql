/*---
id: qb.operator.sponsor-billing
description: "Q87b: how each sponsor of the caller's operator pays the platform's fees - the billing mode in effect, the credit, what is unpaid or not invoiced yet, and who set it why"
entities: [QbSponsor, QbSponsorOperator, QbOperatorMember, QbFeeInvoice, QbFeeCharge, SysParam]
datasets:
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  q: { kind: { type: text, maxLength: 100 }, description: "Only sponsors whose company or contact name contains this text" }
results:
  userId:          { from: QbSponsor.userId }
  company:         { from: QbSponsor.company }
  contactName:     { from: QbSponsor.contactName }
  reviewStatus:    { from: QbSponsor.reviewStatus }
  billingMode:     { kind: { type: text, maxLength: 10 } }
  own:             { kind: { type: bool } }
  creditLimit:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  unpaidTotal:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  overdueCount:    { kind: { type: numeric, precision: 9, scale: 0 } }
  uninvoicedTotal: { kind: { type: monetary, currency: JPY, scale: 0 } }
  billingReason:   { from: QbSponsor.billingReason }
  billingSetBy:    { from: QbSponsor.billingSetBy }
  billingSetAt:    { from: QbSponsor.billingSetAt }
list:
  filters: [billingMode, reviewStatus]
  sorts:   [company, unpaidTotal, billingSetAt]
  defaultSort: { field: company, asc: true }
  key: [userId]
permissions: [qb.operator.use]
---*/
-- Only the sponsors the caller's operator looks after (the membership dataset is the caller's own; Q59c-2). The
-- billing mode in effect is the sponsor's own ("own"), else the platform's default. Unpaid: the invoices open or
-- overdue; not invoiced yet: the postpaid charges the month's invoice (or paying early) has not covered.
SELECT
    s.{{QbSponsor.userId}}        AS userId,
    s.{{QbSponsor.company}}       AS company,
    s.{{QbSponsor.contactName}}   AS contactName,
    s.{{QbSponsor.reviewStatus}}  AS reviewStatus,
    CAST(coalesce(s.{{QbSponsor.billingMode}}, d.d_mode, 'PREPAID') AS varchar(10)) AS billingMode,
    s.{{QbSponsor.billingMode}} IS NOT NULL AS own,
    coalesce(s.{{QbSponsor.creditLimit}}, 0) AS creditLimit,
    coalesce(f.f_total, 0)        AS unpaidTotal,
    coalesce(f.f_overdue, 0)      AS overdueCount,
    coalesce(u.u_total, 0)        AS uninvoicedTotal,
    s.{{QbSponsor.billingReason}} AS billingReason,
    s.{{QbSponsor.billingSetBy}}  AS billingSetBy,
    s.{{QbSponsor.billingSetAt}}  AS billingSetAt
FROM {{QbSponsor}} s
JOIN {{QbSponsorOperator}} a ON a.{{QbSponsorOperator.userId}} = s.{{QbSponsor.userId}}
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
  AND me.{{QbOperatorMember.operatorId}} = a.{{QbSponsorOperator.operatorId}}
LEFT JOIN LATERAL (
    SELECT sum(i.{{QbFeeInvoice.total}}) AS f_total,
           count(*) FILTER (WHERE i.{{QbFeeInvoice.status}} = 'OVERDUE') AS f_overdue
    FROM {{QbFeeInvoice}} i
    WHERE i.{{QbFeeInvoice.sponsorId}} = s.{{QbSponsor.userId}}
      AND i.{{QbFeeInvoice.status}} IN ('OPEN', 'OVERDUE')) f ON true
LEFT JOIN LATERAL (
    SELECT sum(c.{{QbFeeCharge.total}}) AS u_total
    FROM {{QbFeeCharge}} c
    WHERE c.{{QbFeeCharge.sponsorId}} = s.{{QbSponsor.userId}}
      AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
      AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} v
                      WHERE v.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                        AND (v.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
                             OR (v.{{QbFeeInvoice.chargeId}} IS NULL
                                 AND v.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}})))) u ON true
LEFT JOIN LATERAL (
    SELECT CASE WHEN p.{{SysParam.value}} IN ('PREPAID', 'POSTPAID') THEN p.{{SysParam.value}} END AS d_mode
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.fee.default-billing-mode') d ON true
WHERE (CAST(:q AS text) IS NULL
       OR strpos(lower(s.{{QbSponsor.company}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(s.{{QbSponsor.contactName}}), lower(CAST(:q AS text))) > 0)
