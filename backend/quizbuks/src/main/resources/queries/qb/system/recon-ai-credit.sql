/*---
id: qb.system.recon-ai-credit
description: Whether the AI credit and the AI fee agree with the ledger, the movements and the jobs, by sponsor (F-M9a-2b) - the credit against 2180 of the purpose AI, its movements and what was bought; each AI charge against its jobs' fees; jobs not charged after their month; finished jobs still holding a reservation; expired or voided granted credit left in the credit beyond what the jobs still holding credit hold (the daily reconciliation; differences only)
entities: [LedgerEntry, LedgerAccount, QbPrepayment, QbAiCreditMovement, QbAiJob, QbFeeCharge, QbFeePayment, QbFeeCreditGrant, SysParam, QbReconRun, QbReconItem]
datasets:
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbAiCreditMovement: urn:jabiz:dataset:default:QbAiCreditMovement
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  SysParam: urn:jabiz:dataset:now:SysParam
  QbReconRun: urn:jabiz:dataset:default:QbReconRun
  QbReconItem: urn:jabiz:dataset:default:QbReconItem
params:
  at: { like: QbAiJob.finishedAt, description: "The run's time (none: the charges, the unbilled jobs and the expired granted credit are not checked)" }
  zone: { kind: { type: text, maxLength: 64 }, description: "The fiscal year's zone, whose months the AI fees are kept by (none: the unbilled jobs are not checked)" }
  unbilledHours: { kind: { type: numeric, precision: 3, scale: 0 }, description: "qb.ai.fee.unbilled-after-hours (none: the unbilled jobs are not checked)" }
  expiredHours: { kind: { type: numeric, precision: 3, scale: 0 }, description: "qb.ai.credit.expired-held-after-hours (none: the expired granted credit is not checked)" }
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
-- AI_CREDIT (F-M9 plan §8): by sponsor, the open AI credit's remaining against 2180 of the purpose AI (dimension 3, the
--   reference "ledger"; a balance without an open credit too), against the sum of its movements ("movements": bought,
--   charged, given back, expired), and its amount (what was bought in all) against the movements BUY ("bought").
--   Scale: the 2180 lines of the purpose AI and the movements, a few a sponsor a month.
-- AI_FEE_MONTH (plan §8, #699 review L3): by sponsor and charge (the reference), an AI charge's amount against the fees
--   of the jobs it took in as they are after it (lowered when the credit could not hold them); charges of the last 40
--   days before :at (a charge's amount and its jobs do not change after it), and those the latest run before :at
--   reported (#702 review M4: a difference found stays until it is resolved, however old its charge), the jobs found
--   by qb_ai_job_fee_charge_idx.
-- AI_FEE_UNBILLED (plan §8): by sponsor and month (the reference), the charged jobs with a fee still not in a charge
--   qb.ai.fee.unbilled-after-hours after their month (fee_period, in :zone) ended: expected 0, actual their fees; a
--   finished job with a fee and no month at once (#702 review L2, the reference "none": no charge would take it in).
--   The monthly job runs on the 1st; a late outcome is charged by a run requested again (operations.md §7.56).
-- AI_FEE_RESERVED (plan §8): by sponsor, finished charged jobs still holding a reservation (the outcome lets it go):
--   expected 0, actual what they hold. Read through qb_ai_job_fee_open_idx (its condition is in the WHERE).
-- AI_CREDIT_EXPIRED_HELD (plan §8): by sponsor, the fee credit the open AI credit still holds of grants expired, or
--   voided before they expired (#699 re-review M1), qb.ai.credit.expired-held-after-hours before :at, beyond what the
--   sponsor's jobs still holding credit hold (#702 review L1: running or finished and not charged yet) (their reservations, and their fees with tax rounded up at qb.fee.tax-rate, 100% when not
--   set: never under what they hold, so never a false difference): expected what they hold (at most what is expired),
--   actual what is expired. The daily job qb.ai-credit-expire voids it; one found here is a run that failed or did not
--   run.
WITH credits AS (
    SELECT p.{{QbPrepayment.prepaymentId}} AS c_id, p.{{QbPrepayment.sponsorId}} AS c_sponsor,
           p.{{QbPrepayment.amount}} AS c_amount, p.{{QbPrepayment.remaining}} AS c_remaining
    FROM {{QbPrepayment}} p
    WHERE p.{{QbPrepayment.purpose}} = 'AI' AND p.{{QbPrepayment.status}} = 'OPEN'
), ai_ledger AS (
    SELECT e.{{LedgerEntry.dimension1}} AS r_party,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2180' AND e.{{LedgerEntry.dimension3}} = 'AI'
    GROUP BY e.{{LedgerEntry.dimension1}}
), moves AS (
    SELECT m.{{QbAiCreditMovement.sponsorId}} AS r_sponsor, sum(m.{{QbAiCreditMovement.amount}}) AS r_sum,
           sum(CASE WHEN m.{{QbAiCreditMovement.kind}} = 'BUY' THEN m.{{QbAiCreditMovement.amount}} ELSE 0 END)
               AS r_bought
    FROM {{QbAiCreditMovement}} m
    GROUP BY m.{{QbAiCreditMovement.sponsorId}}
), last_run AS (
    -- The latest run before this one (its items as it kept them).
    SELECT r.{{QbReconRun.runId}} AS r_run
    FROM {{QbReconRun}} r
    WHERE r.{{QbReconRun.scheduledTime}} < CAST(:at AS timestamptz)
    ORDER BY r.{{QbReconRun.scheduledTime}} DESC
    LIMIT 1
), reported AS (
    SELECT DISTINCT i.{{QbReconItem.reference}} AS r_ref
    FROM {{QbReconItem}} i JOIN last_run l ON i.{{QbReconItem.runId}} = l.r_run
    WHERE i.{{QbReconItem.check}} = 'AI_FEE_MONTH'
), recent_charges AS (
    SELECT c.{{QbFeeCharge.chargeId}} AS r_charge, c.{{QbFeeCharge.sponsorId}} AS r_sponsor,
           c.{{QbFeeCharge.amount}} AS r_amount
    FROM {{QbFeeCharge}} c
    WHERE c.{{QbFeeCharge.kind}} = 'AI' AND CAST(:at AS timestamptz) IS NOT NULL
      AND (c.{{QbFeeCharge.chargedAt}} >= CAST(:at AS timestamptz) - interval '40 days'
           OR CAST(c.{{QbFeeCharge.chargeId}} AS text) IN (SELECT r_ref FROM reported))
), charged_jobs AS (
    -- #702 review L4: a join on the charge (qb_ai_job_fee_charge_idx, whose condition is the IS NOT NULL).
    SELECT j.{{QbAiJob.feeChargeId}} AS r_charge, sum(j.{{QbAiJob.feeYen}}) AS r_fees
    FROM recent_charges r
    JOIN {{QbAiJob}} j ON j.{{QbAiJob.feeChargeId}} = CAST(r.r_charge AS text)
    WHERE j.{{QbAiJob.feeChargeId}} IS NOT NULL
    GROUP BY j.{{QbAiJob.feeChargeId}}
), open_jobs AS (
    -- The open set (qb_ai_job_fee_open_idx): reserved, or finished with a fee not in a charge.
    SELECT j.{{QbAiJob.sponsorId}} AS r_sponsor, j.{{QbAiJob.feePeriod}} AS r_period,
           j.{{QbAiJob.finishedAt}} AS r_finished, j.{{QbAiJob.feeYen}} AS r_fee, j.{{QbAiJob.reservedYen}} AS r_reserved
    FROM {{QbAiJob}} j
    WHERE j.{{QbAiJob.feeMode}} = 'CREDIT' AND j.{{QbAiJob.feeChargeId}} IS NULL
      AND (j.{{QbAiJob.feeYen}} IS NULL OR j.{{QbAiJob.feeYen}} > 0)
), tax AS (
    SELECT coalesce(max(CAST(p.{{SysParam.value}} AS numeric)), 1) AS r_rate
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.fee.tax-rate'
), occupied AS (
    SELECT o.r_sponsor, sum(CASE WHEN o.r_finished IS NULL THEN coalesce(o.r_reserved, 0)
                                 ELSE ceil(o.r_fee * (1 + t.r_rate)) END) AS r_held
    FROM open_jobs o CROSS JOIN tax t
    GROUP BY o.r_sponsor
), parts AS (
    -- What each open credit holds of each grant, as qb.system.ai-credit-held reads one credit.
    SELECT c.c_sponsor AS r_sponsor, p.{{QbFeePayment.grantId}} AS r_grant, p.{{QbFeePayment.amount}} AS r_amount
    FROM credits c JOIN {{QbFeePayment}} p ON p.{{QbFeePayment.targetId}} = CAST(c.c_id AS text)
    WHERE p.{{QbFeePayment.grantId}} IS NOT NULL AND CAST(:expiredHours AS numeric) IS NOT NULL
    UNION ALL
    SELECT c.c_sponsor, u.{{QbFeePayment.grantId}}, -u.{{QbFeePayment.amount}}
    FROM credits c JOIN {{QbFeePayment}} u ON u.{{QbFeePayment.prepaymentId}} = CAST(c.c_id AS text)
    WHERE u.{{QbFeePayment.grantId}} IS NOT NULL AND u.{{QbFeePayment.amount}} > 0
      AND CAST(:expiredHours AS numeric) IS NOT NULL
), expired AS (
    SELECT h.r_sponsor, sum(h.r_held) AS r_expired
    FROM (SELECT r_sponsor, r_grant, sum(r_amount) AS r_held FROM parts GROUP BY r_sponsor, r_grant) h
    JOIN {{QbFeeCreditGrant}} g ON g.{{QbFeeCreditGrant.grantId}} = h.r_grant
    WHERE h.r_held > 0
      AND least(g.{{QbFeeCreditGrant.expiresAt}}, coalesce(g.{{QbFeeCreditGrant.voidedAt}},
                g.{{QbFeeCreditGrant.expiresAt}}))
          <= CAST(:at AS timestamptz) - CAST(:expiredHours AS numeric) * interval '1 hour'
    GROUP BY h.r_sponsor
)
SELECT CAST('AI_CREDIT' AS varchar(30)) AS checkCode, CAST(c.c_sponsor AS varchar(128)) AS sponsorId,
       CAST('ledger' AS varchar(128)) AS reference, c.c_remaining AS expected, coalesce(l.r_balance, 0) AS actual
FROM credits c LEFT JOIN ai_ledger l ON l.r_party = c.c_sponsor
WHERE c.c_remaining <> coalesce(l.r_balance, 0)
UNION ALL
SELECT 'AI_CREDIT', CAST(l.r_party AS varchar(128)), 'ledger', 0, l.r_balance
FROM ai_ledger l
WHERE l.r_balance <> 0 AND NOT EXISTS (SELECT 1 FROM credits c WHERE c.c_sponsor = l.r_party)
UNION ALL
SELECT 'AI_CREDIT', c.c_sponsor, 'movements', c.c_remaining, coalesce(m.r_sum, 0)
FROM credits c LEFT JOIN moves m ON m.r_sponsor = c.c_sponsor
WHERE c.c_remaining <> coalesce(m.r_sum, 0)
UNION ALL
SELECT 'AI_CREDIT', c.c_sponsor, 'bought', c.c_amount, coalesce(m.r_bought, 0)
FROM credits c LEFT JOIN moves m ON m.r_sponsor = c.c_sponsor
WHERE c.c_amount <> coalesce(m.r_bought, 0)
UNION ALL
SELECT 'AI_FEE_MONTH', r.r_sponsor, CAST(r.r_charge AS varchar(128)), r.r_amount, coalesce(j.r_fees, 0)
FROM recent_charges r LEFT JOIN charged_jobs j ON j.r_charge = CAST(r.r_charge AS text)
WHERE r.r_amount <> coalesce(j.r_fees, 0)
UNION ALL
SELECT 'AI_FEE_UNBILLED', o.r_sponsor, CAST(coalesce(o.r_period, 'none') AS varchar(128)), 0, sum(o.r_fee)
FROM open_jobs o
WHERE o.r_finished IS NOT NULL AND o.r_fee > 0 AND CAST(:unbilledHours AS numeric) IS NOT NULL
  AND CAST(:zone AS text) IS NOT NULL
  AND (o.r_period IS NULL
       OR ((CAST(o.r_period || '-01' AS date) + interval '1 month') AT TIME ZONE CAST(:zone AS text))
          + CAST(:unbilledHours AS numeric) * interval '1 hour' <= CAST(:at AS timestamptz))
GROUP BY o.r_sponsor, o.r_period
UNION ALL
SELECT 'AI_FEE_RESERVED', o.r_sponsor, 'reserved', 0, sum(o.r_reserved)
FROM open_jobs o
WHERE o.r_finished IS NOT NULL AND o.r_reserved > 0
GROUP BY o.r_sponsor
UNION ALL
SELECT 'AI_CREDIT_EXPIRED_HELD', e.r_sponsor, 'expired', least(e.r_expired, coalesce(h.r_held, 0)), e.r_expired
FROM expired e LEFT JOIN occupied h ON h.r_sponsor = e.r_sponsor
WHERE e.r_expired > coalesce(h.r_held, 0)
