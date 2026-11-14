/*---
id: qb.operator.sponsor-ai
description: "F-M9: one sponsor of the caller's operator and its AI fee - the AI credit it holds, the AI fee of a month charged under the caller's operator (frozen on each job) and how many jobs it was charged for"
entities: [QbSponsor, QbSponsorOperator, QbOperatorMember, QbPrepayment, QbAiJob]
datasets:
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
params:
  sponsorId: { like: QbSponsor.userId, required: true, description: "The sponsor" }
  month: { kind: { type: text, maxLength: 7 }, required: true, description: "The month of the fee (YYYY-MM, the fiscal year's zone)" }
results:
  userId:    { from: QbSponsor.userId }
  company:   { from: QbSponsor.company }
  aiCredit:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  monthFee:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  monthJobs: { kind: { type: numeric, precision: 12, scale: 0 } }
list:
  sorts: [company]
  defaultSort: { field: company, asc: true }
  key: [userId]
permissions: [qb.operator.use]
---*/
-- Only a sponsor the caller's operator looks after, as qb.operator.sponsor-billing: the membership dataset is the
-- caller's own, so a sponsor assigned to another operator (or to none any more) has no row. The credit is what its open
-- AI prepayments still hold (before tax, as the sponsor sees it); the month's fee is what the jobs whose result was
-- recorded in the month charged (feeYen, before tax) under the caller's operator, counting the jobs that carry a fee.
-- QbAiJob and QbPrepayment are default datasets with no scope of their own: the limit to the caller's operator comes
-- from s alone (QbSponsor joined to the sponsor's assignment and to the caller's own membership). Keep the two
-- subqueries LATERAL below s; as plain joins or a template of their own they would show another operator's sponsor.
SELECT
    s.{{QbSponsor.userId}}  AS userId,
    s.{{QbSponsor.company}} AS company,
    CAST(coalesce(c.c_remaining, 0) AS numeric(19, 0)) AS aiCredit,
    CAST(coalesce(j.j_fee, 0) AS numeric(19, 0))       AS monthFee,
    CAST(coalesce(j.j_jobs, 0) AS numeric(12, 0))      AS monthJobs
FROM {{QbSponsor}} s
JOIN {{QbSponsorOperator}} a ON a.{{QbSponsorOperator.userId}} = s.{{QbSponsor.userId}}
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
  AND me.{{QbOperatorMember.operatorId}} = a.{{QbSponsorOperator.operatorId}}
LEFT JOIN LATERAL (
    SELECT sum(p.{{QbPrepayment.remaining}}) AS c_remaining
    FROM {{QbPrepayment}} p
    WHERE p.{{QbPrepayment.sponsorId}} = s.{{QbSponsor.userId}}
      AND p.{{QbPrepayment.purpose}} = 'AI' AND p.{{QbPrepayment.status}} = 'OPEN') c ON true
LEFT JOIN LATERAL (
    SELECT sum(x.{{QbAiJob.feeYen}}) AS j_fee,
           count(*) FILTER (WHERE x.{{QbAiJob.feeYen}} > 0) AS j_jobs
    FROM {{QbAiJob}} x
    WHERE x.{{QbAiJob.sponsorId}} = s.{{QbSponsor.userId}}
      AND x.{{QbAiJob.feePeriod}} = :month
      -- Only the jobs whose fee is the caller's operator's: the operator is frozen on the job when it is asked (as the
      -- operators' settlements count the fees by the operator of the charge), so a sponsor who moves takes none of the
      -- fees of before to the new operator.
      AND x.{{QbAiJob.feeOperatorId}} = CAST(me.{{QbOperatorMember.operatorId}} AS text)) j ON true
WHERE s.{{QbSponsor.userId}} = :sponsorId
