/*---
id: qb.admin.money-closures
description: "F-M15 b §8: the wind-ups of erased sponsors' platform fee accounts, one row a sponsor - state, why the last run stopped, what is still owed, and whether it is waiting past qb.erasure.close.alert-days (headquarters' finance, read only)"
entities: [QbSponsorClosure, QbFeeInvoice, SysParam]
datasets:
  QbSponsorClosure: urn:jabiz:dataset:default:QbSponsorClosure
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at:   { like: QbSponsorClosure.erasedAt, required: true, description: "Now, by the server's clock (the page gives it)" }
  show: { kind: { type: text, maxLength: 10 }, description: "OPEN (not done), OWED (done with fees owed), SLOW (waiting past the alert days); none: all" }
results:
  sponsorId:  { from: QbSponsorClosure.sponsorId }
  status:     { from: QbSponsorClosure.status }
  reasonCode: { from: QbSponsorClosure.reasonCode }
  erasedAt:   { from: QbSponsorClosure.erasedAt }
  lastRunAt:  { from: QbSponsorClosure.lastRunAt }
  closedAt:   { from: QbSponsorClosure.closedAt }
  alertedAt:  { from: QbSponsorClosure.alertedAt }
  owed:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  slow:       { kind: { type: bool } }
list:
  sorts: [erasedAt, closedAt, owed]
  defaultSort: { field: erasedAt, asc: false }
  key: [sponsorId]
permissions: [qb.admin.finance.read]
---*/
-- owed is what the unpaid invoices still owe (total - credit applied - paid, as QbFees.owed). slow is the wind-up's own
-- rule (ErasureCloseRules.slow): WAITING and qb.erasure.close.alert-days or more after the erasure; a value that is not
-- a whole number from 1 to 60 (or none) counts as 0, the same as the reconciliation's ERASED_CLOSE_OPEN.
WITH days AS (
    SELECT coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,2}$'
                                  AND CAST(p.{{SysParam.value}} AS int) BETWEEN 1 AND 60
                             THEN CAST(p.{{SysParam.value}} AS int) END), 0) AS d_days
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.erasure.close.alert-days'
),
rows AS (
    SELECT c.{{QbSponsorClosure.sponsorId}} AS r_sponsor, c.{{QbSponsorClosure.status}} AS r_status,
           c.{{QbSponsorClosure.reasonCode}} AS r_reason, c.{{QbSponsorClosure.erasedAt}} AS r_erased,
           c.{{QbSponsorClosure.lastRunAt}} AS r_last, c.{{QbSponsorClosure.closedAt}} AS r_closed,
           c.{{QbSponsorClosure.alertedAt}} AS r_alerted,
           coalesce((SELECT sum(i.{{QbFeeInvoice.total}} - i.{{QbFeeInvoice.creditApplied}} - i.{{QbFeeInvoice.paidAmount}})
                     FROM {{QbFeeInvoice}} i
                     WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbSponsorClosure.sponsorId}}
                       AND i.{{QbFeeInvoice.status}} IN ('OPEN', 'PARTIAL', 'OVERDUE')), 0) AS r_owed,
           (c.{{QbSponsorClosure.status}} = 'WAITING'
            AND c.{{QbSponsorClosure.erasedAt}} + make_interval(days => d.d_days) <= CAST(:at AS timestamptz)) AS r_slow
    FROM {{QbSponsorClosure}} c
    CROSS JOIN days d
)
SELECT r_sponsor AS sponsorId, r_status AS status, r_reason AS reasonCode, r_erased AS erasedAt, r_last AS lastRunAt,
       r_closed AS closedAt, r_alerted AS alertedAt, r_owed AS owed, r_slow AS slow
FROM rows
WHERE CAST(:show AS text) IS NULL
   OR (:show = 'OPEN' AND r_status = 'WAITING')
   OR (:show = 'OWED' AND r_status = 'CLOSED' AND r_owed > 0)
   OR (:show = 'SLOW' AND r_slow)
