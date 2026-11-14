/*---
id: qb.sponsor.ai-credit
description: "The Sponsor's AI credit at a glance (F-M9b): what it holds, what was bought in all, what the requests still running hold, and the fees of finished requests not charged yet (before tax: the month's charge adds it); one row, zeros without a credit"
entities: [QbPrepayment, QbAiJob]
datasets:
  QbPrepayment: urn:jabiz:dataset:sponsor:QbPrepayment
  QbAiJob: urn:jabiz:dataset:sponsor:QbAiJob
results:
  remaining:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  bought:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  reserved:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  unbilled:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  unbilledJobs:  { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [remaining]
  defaultSort: { field: remaining, asc: true }
  key: [remaining]
permissions: [qb.sponsor.finance.read]
---*/
-- The datasets are the caller's own. The open AI credit (one a sponsor); the charged jobs still holding part of it (the
-- open set of qb_ai_job_fee_open_idx: reserved, or finished with a fee not in a charge).
SELECT coalesce(c.c_remaining, 0) AS remaining, coalesce(c.c_amount, 0) AS bought,
       coalesce(j.j_reserved, 0) AS reserved, coalesce(j.j_unbilled, 0) AS unbilled,
       coalesce(j.j_count, 0) AS unbilledJobs
FROM (SELECT 1 AS one) base
LEFT JOIN (
    SELECT sum(p.{{QbPrepayment.remaining}}) AS c_remaining, sum(p.{{QbPrepayment.amount}}) AS c_amount
    FROM {{QbPrepayment}} p
    WHERE p.{{QbPrepayment.purpose}} = 'AI' AND p.{{QbPrepayment.status}} = 'OPEN') c ON true
LEFT JOIN (
    SELECT sum(CASE WHEN x.{{QbAiJob.finishedAt}} IS NULL THEN x.{{QbAiJob.reservedYen}} ELSE 0 END) AS j_reserved,
           sum(CASE WHEN x.{{QbAiJob.finishedAt}} IS NOT NULL THEN x.{{QbAiJob.feeYen}} ELSE 0 END) AS j_unbilled,
           count(*) FILTER (WHERE x.{{QbAiJob.finishedAt}} IS NOT NULL) AS j_count
    FROM {{QbAiJob}} x
    WHERE x.{{QbAiJob.feeMode}} = 'CREDIT' AND x.{{QbAiJob.feeChargeId}} IS NULL
      AND (x.{{QbAiJob.feeYen}} IS NULL OR x.{{QbAiJob.feeYen}} > 0)) j ON true
