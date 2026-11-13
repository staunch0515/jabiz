/*---
id: qb.system.recon-prepayments
description: Whether the prepayments agree with the ledger and their parts, by sponsor (F-M5) - the prepayments (2180) with what their open prepayments hold, no prepayment held for a publication back in its draft; and (F-M6) the distribution fee's deposits, settlements and prices (the daily reconciliation; differences only, and one note) (one of the four templates of the daily fee-account reconciliation, F-M14 G10)
entities: [QbFeePayment, LedgerEntry, LedgerAccount, QbPrepayment, QbPublication, QbPublicationStartCounter, SysParam, QbPublicationCard, QbDistributionSettlement, QbDistributionPrice]
datasets:
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  SysParam: urn:jabiz:dataset:now:SysParam
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbDistributionSettlement: urn:jabiz:dataset:default:QbDistributionSettlement
  QbDistributionPrice: urn:jabiz:dataset:default:QbDistributionPrice
params:
  at: { like: QbPublication.endedAt, description: "The run's time: an ended publication's prepayment still open a while after it is a difference (none: not checked)" }
  zone: { kind: { type: text, maxLength: 64 }, description: "F-M6: the fiscal year's zone, whose months the distribution fee is settled by (none: the unsettled months are not checked)" }
  unsettledHours: { kind: { type: numeric, precision: 3, scale: 0 }, description: "F-M6: qb.distribution.unsettled-after-hours (none: the unsettled months are not checked)" }
  missingHours: { kind: { type: numeric, precision: 3, scale: 0 }, description: "F-M6: qb.distribution.final-missing-after-hours (none: the missing final rows are not checked)" }
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
-- PREPAYMENTS (F-M5): 2180 for a sponsor (credit less debit) against what their open prepaid prepayments hold.
-- PREPAYMENT_STALE (F-M5): by sponsor, the open prepayments that should have been given back: of a publication back
--   in its draft (withdrawn, rejected, its review expired), of one ended more than qb.prepayment.release.overdue-days
--   before :at (the daily job gives it back within a day; 1 to 30, anything else or none: 2), or of a publication that
--   is not there: expected 0, actual what they hold (a postpaid one: what it still counts).
-- PREPAYMENT_UNCHARGED (F-M5 recheck N1): by sponsor, the audience fees not charged though the publication started
--   (live or underfunded, or with a start already): expected 0, actual the fees before tax.
-- DISTRIBUTION_DEPOSITS (F-M6): by sponsor and publication (the reference), 2180 on the lines of the purpose
--   DISTRIBUTION (dimension 3) against what the publication's open prepaid DISTRIBUTION prepayment holds.
-- DISTRIBUTION_UNSETTLED (F-M6): by sponsor and month (the reference), every month before :at's a publication with a
--   price per start ran in without the month's row or a final one, qb.distribution.unsettled-after-hours (handed in) after the
--   month's end (the condition of qb.system.distribution-due and of the invoices' readiness): expected 0, actual how
--   many publications. Not checked without :zone.
-- DISTRIBUTION_FINAL_MISSING (F-M6): by sponsor, the ended publications with a price per start and no final row
--   qb.distribution.final-missing-after-hours (handed in) after their end: expected 0, actual how many.
-- DISTRIBUTION_PRICE_RANGE (F-M6): by operator (sponsorId holds it), a price outside the headquarters' range as
--   the parameters give it now (a bound not set does not bound): expected 0, actual 1.
-- DISTRIBUTION_AT_FLOOR (F-M6, a note, not a difference): by sponsor, the publications not ended whose price per start
--   is the operator's floor (their top tier set it no higher): expected 0, actual how many.
-- PREPAYMENT_PARTS (F-M5 review M3): by sponsor, the prepaid prepayments whose parts (set aside, given back, less
--   what fees took of them) do not add up to what they hold: expected what they hold, actual what the parts say.
WITH prepayment_ledger AS (
    SELECT e.{{LedgerEntry.dimension1}} AS r_party,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2180'
    GROUP BY e.{{LedgerEntry.dimension1}}
),
distribution_ledger AS (
    -- M4 (review): by sponsor and publication (dimension 2), so that two publications' errors cannot cancel out.
    SELECT e.{{LedgerEntry.dimension1}} AS r_party, e.{{LedgerEntry.dimension2}} AS r_publication,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2180' AND e.{{LedgerEntry.dimension3}} = 'DISTRIBUTION'
    GROUP BY e.{{LedgerEntry.dimension1}}, e.{{LedgerEntry.dimension2}}
),
distribution_held AS (
    SELECT p.{{QbPrepayment.sponsorId}} AS r_party, CAST(p.{{QbPrepayment.publicationId}} AS text) AS r_publication,
           sum(p.{{QbPrepayment.remaining}}) AS r_remaining
    FROM {{QbPrepayment}} p
    WHERE p.{{QbPrepayment.status}} = 'OPEN' AND p.{{QbPrepayment.mode}} = 'PREPAID'
      AND p.{{QbPrepayment.purpose}} = 'DISTRIBUTION'
    GROUP BY p.{{QbPrepayment.sponsorId}}, p.{{QbPrepayment.publicationId}}
),
distribution_params AS (
    -- L4 (review): the two hours are QbParams' values handed in by the reconciliation (no default here); either
    -- missing, its check is not made (SetupParamsCheck reports the missing parameter).
    SELECT CAST(:unsettledHours AS int) AS unsettled_hours,
           CAST(:missingHours AS int) AS missing_hours,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.distribution.rate.min' THEN p.{{SysParam.value}} END) AS rate_min,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.distribution.rate.max' THEN p.{{SysParam.value}} END) AS rate_max,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.distribution.unit-floor.min' THEN p.{{SysParam.value}} END)
               AS floor_min,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.distribution.unit-floor.max' THEN p.{{SysParam.value}} END)
               AS floor_max,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.distribution.unit-ceiling.max' THEN p.{{SysParam.value}} END)
               AS ceiling_max,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.distribution.minimum-deposit.max' THEN p.{{SysParam.value}} END)
               AS deposit_max
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} LIKE 'qb.distribution.%'
),
distribution_month AS (
    -- The month before :at in :zone: its first instant, the next month's, and its name.
    SELECT (date_trunc('month', CAST(:at AS timestamptz) AT TIME ZONE CAST(:zone AS text)) - interval '1 month')
               AT TIME ZONE CAST(:zone AS text) AS m_start,
           date_trunc('month', CAST(:at AS timestamptz) AT TIME ZONE CAST(:zone AS text))
               AT TIME ZONE CAST(:zone AS text) AS m_end,
           to_char(date_trunc('month', CAST(:at AS timestamptz) AT TIME ZONE CAST(:zone AS text)) - interval '1 month',
               'YYYY-MM') AS m_name
    WHERE CAST(:at AS timestamptz) IS NOT NULL AND CAST(:zone AS text) IS NOT NULL
),
prepayments_held AS (
    SELECT p.{{QbPrepayment.sponsorId}} AS r_party, sum(p.{{QbPrepayment.remaining}}) AS r_remaining
    FROM {{QbPrepayment}} p
    WHERE p.{{QbPrepayment.status}} = 'OPEN' AND p.{{QbPrepayment.mode}} = 'PREPAID'
    GROUP BY p.{{QbPrepayment.sponsorId}}
)
SELECT CAST('PREPAYMENTS' AS varchar(30)) AS checkCode,
       CAST(coalesce(ph.r_party, pl.r_party) AS varchar(128)) AS sponsorId, CAST(NULL AS varchar(128)) AS reference,
       coalesce(ph.r_remaining, 0) AS expected, coalesce(pl.r_balance, 0) AS actual
FROM prepayments_held ph
FULL OUTER JOIN prepayment_ledger pl ON pl.r_party = ph.r_party
WHERE coalesce(ph.r_remaining, 0) <> coalesce(pl.r_balance, 0)
UNION ALL
SELECT CAST('PREPAYMENT_STALE' AS varchar(30)), CAST(pp.{{QbPrepayment.sponsorId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), sum(pp.{{QbPrepayment.remaining}})
FROM {{QbPrepayment}} pp
LEFT JOIN {{QbPublication}} pub ON CAST(pub.{{QbPublication.publicationId}} AS text) = pp.{{QbPrepayment.publicationId}}
CROSS JOIN (
    SELECT coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,2}$'
                                  AND CAST(p.{{SysParam.value}} AS int) BETWEEN 1 AND 30
                             THEN CAST(p.{{SysParam.value}} AS int) END), 2) AS days
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.prepayment.release.overdue-days') params
WHERE pp.{{QbPrepayment.status}} = 'OPEN'
  AND pp.{{QbPrepayment.publicationId}} IS NOT NULL
  AND (pub.{{QbPublication.publicationId}} IS NULL
       OR pub.{{QbPublication.status}} = 'DRAFT'
       OR (pub.{{QbPublication.status}} = 'ENDED' AND CAST(:at AS timestamptz) IS NOT NULL
           AND pub.{{QbPublication.endedAt}} < CAST(:at AS timestamptz) - make_interval(days => params.days)))
GROUP BY pp.{{QbPrepayment.sponsorId}}
UNION ALL
SELECT CAST('PREPAYMENT_PARTS' AS varchar(30)), CAST(pp.{{QbPrepayment.sponsorId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       sum(pp.{{QbPrepayment.remaining}}), sum(coalesce(own.r_amount, 0) - coalesce(taken.r_amount, 0))
FROM {{QbPrepayment}} pp
LEFT JOIN (SELECT f.{{QbFeePayment.targetId}} AS r_id, sum(f.{{QbFeePayment.amount}}) AS r_amount
           FROM {{QbFeePayment}} f
           WHERE f.{{QbFeePayment.targetEntity}} = 'QbPrepayment'
           GROUP BY f.{{QbFeePayment.targetId}}) own ON own.r_id = pp.{{QbPrepayment.prepaymentId}}
-- What fees took of it: its positive parts only (a voided invoice gives what it paid back to the account, not to the
-- prepayment: its negative parts name the prepayment too).
LEFT JOIN (SELECT f.{{QbFeePayment.prepaymentId}} AS r_id, sum(f.{{QbFeePayment.amount}}) AS r_amount
           FROM {{QbFeePayment}} f
           WHERE f.{{QbFeePayment.prepaymentId}} IS NOT NULL AND f.{{QbFeePayment.amount}} > 0
           GROUP BY f.{{QbFeePayment.prepaymentId}}) taken ON taken.r_id = pp.{{QbPrepayment.prepaymentId}}
WHERE pp.{{QbPrepayment.mode}} = 'PREPAID'
  AND pp.{{QbPrepayment.remaining}} <> coalesce(own.r_amount, 0) - coalesce(taken.r_amount, 0)
GROUP BY pp.{{QbPrepayment.sponsorId}}
UNION ALL
SELECT CAST('PREPAYMENT_UNCHARGED' AS varchar(30)), CAST(pp.{{QbPrepayment.sponsorId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), sum(pp.{{QbPrepayment.chargeAmount}})
FROM {{QbPrepayment}} pp
JOIN {{QbPublication}} pub ON CAST(pub.{{QbPublication.publicationId}} AS text) = pp.{{QbPrepayment.publicationId}}
LEFT JOIN {{QbPublicationStartCounter}} sc
    ON sc.{{QbPublicationStartCounter.publicationId}} = pub.{{QbPublication.publicationId}}
WHERE pp.{{QbPrepayment.purpose}} = 'AUDIENCE'
  AND pp.{{QbPrepayment.status}} = 'OPEN'
  AND pp.{{QbPrepayment.chargedAt}} IS NULL
  AND pp.{{QbPrepayment.chargeAmount}} > 0
  AND (pub.{{QbPublication.status}} IN ('LIVE', 'FUNDS_SHORT') OR coalesce(sc.{{QbPublicationStartCounter.starts}}, 0) > 0)
GROUP BY pp.{{QbPrepayment.sponsorId}}
UNION ALL
SELECT CAST('DISTRIBUTION_DEPOSITS' AS varchar(30)), CAST(coalesce(dh.r_party, dl.r_party) AS varchar(128)),
       CAST(coalesce(dh.r_publication, dl.r_publication) AS varchar(128)),
       coalesce(dh.r_remaining, 0), coalesce(dl.r_balance, 0)
FROM distribution_held dh
FULL OUTER JOIN distribution_ledger dl ON dl.r_party = dh.r_party AND dl.r_publication = dh.r_publication
WHERE coalesce(dh.r_remaining, 0) <> coalesce(dl.r_balance, 0)
UNION ALL
SELECT CAST('DISTRIBUTION_UNSETTLED' AS varchar(30)), CAST(dp.{{QbPublication.publisherId}} AS varchar(128)),
       CAST(to_char(mo.r_month, 'YYYY-MM') AS varchar(128)), CAST(0 AS numeric), CAST(count(*) AS numeric)
FROM {{QbPublication}} dp
JOIN {{QbPublicationCard}} dc ON dc.{{QbPublicationCard.publicationId}} = dp.{{QbPublication.publicationId}}
CROSS JOIN distribution_month dm
CROSS JOIN distribution_params dpar
-- M2 (review): every month before this one, not only the last: from the month it started in to the month it ended in
-- (or the last month), each month without its row or a final one, once qb.distribution.unsettled-after-hours has
-- passed since that month's end. The months a postpaid sponsor's invoice waits for (qb.system.fee-invoices-due:
-- owed less ready) are among them, and so are a prepaid sponsor's.
CROSS JOIN LATERAL generate_series(
    -- When it went on (N1): greatest(its start, its approval); the same expression in qb.system.distribution-due, qb.system.fee-invoices-due and qb.system.recon-prepayments (DistributionDueConditionTest).
    date_trunc('month', greatest(dc.{{QbPublicationCard.startAt}}, dp.{{QbPublication.approvedAt}}) AT TIME ZONE CAST(:zone AS text)),
    date_trunc('month', least(coalesce(dc.{{QbPublicationCard.endedAt}}, dm.m_end - interval '1 second'),
                              dm.m_end - interval '1 second') AT TIME ZONE CAST(:zone AS text)),
    interval '1 month') AS mo(r_month)
WHERE dp.{{QbPublication.distributionUnit}} IS NOT NULL
  AND dp.{{QbPublication.publisherKind}} = 'SPONSOR'
  AND CAST(:at AS timestamptz) >= (mo.r_month + interval '1 month') AT TIME ZONE CAST(:zone AS text)
                                  + make_interval(hours => dpar.unsettled_hours)
  AND NOT EXISTS (SELECT 1 FROM {{QbDistributionSettlement}} ds
                  WHERE ds.{{QbDistributionSettlement.publicationId}} = CAST(dp.{{QbPublication.publicationId}} AS text)
                    AND (ds.{{QbDistributionSettlement.final}}
                         OR ds.{{QbDistributionSettlement.period}} = to_char(mo.r_month, 'YYYY-MM')))
GROUP BY dp.{{QbPublication.publisherId}}, mo.r_month
UNION ALL
SELECT CAST('DISTRIBUTION_FINAL_MISSING' AS varchar(30)), CAST(dp.{{QbPublication.publisherId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), CAST(count(*) AS numeric)
FROM {{QbPublication}} dp
JOIN {{QbPublicationCard}} dc ON dc.{{QbPublicationCard.publicationId}} = dp.{{QbPublication.publicationId}}
CROSS JOIN distribution_params dpar
WHERE dp.{{QbPublication.distributionUnit}} IS NOT NULL
  AND dp.{{QbPublication.publisherKind}} = 'SPONSOR'
  AND dc.{{QbPublicationCard.status}} = 'ENDED'
  AND CAST(:at AS timestamptz) IS NOT NULL
  AND dc.{{QbPublicationCard.endedAt}} < CAST(:at AS timestamptz) - make_interval(hours => dpar.missing_hours)
  AND NOT EXISTS (SELECT 1 FROM {{QbDistributionSettlement}} ds
                  WHERE ds.{{QbDistributionSettlement.publicationId}} = CAST(dp.{{QbPublication.publicationId}} AS text)
                    AND ds.{{QbDistributionSettlement.final}})
GROUP BY dp.{{QbPublication.publisherId}}
UNION ALL
SELECT CAST('DISTRIBUTION_PRICE_RANGE' AS varchar(30)), CAST(pr.{{QbDistributionPrice.operatorId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), CAST(1 AS numeric)
FROM {{QbDistributionPrice}} pr
CROSS JOIN distribution_params dpar
WHERE (dpar.rate_min ~ '^[0-9]+(\.[0-9]+)?$' AND pr.{{QbDistributionPrice.rate}} < CAST(dpar.rate_min AS numeric))
   OR (dpar.rate_max ~ '^[0-9]+(\.[0-9]+)?$' AND pr.{{QbDistributionPrice.rate}} > CAST(dpar.rate_max AS numeric))
   OR (dpar.floor_min ~ '^[0-9]+(\.[0-9]+)?$'
       AND pr.{{QbDistributionPrice.unitFloor}} < CAST(dpar.floor_min AS numeric))
   OR (dpar.floor_max ~ '^[0-9]+(\.[0-9]+)?$'
       AND pr.{{QbDistributionPrice.unitFloor}} > CAST(dpar.floor_max AS numeric))
   OR (dpar.ceiling_max ~ '^[0-9]+(\.[0-9]+)?$'
       AND pr.{{QbDistributionPrice.unitCeiling}} > CAST(dpar.ceiling_max AS numeric))
   OR (dpar.deposit_max ~ '^[0-9]+(\.[0-9]+)?$'
       AND pr.{{QbDistributionPrice.minimumDeposit}} > CAST(dpar.deposit_max AS numeric))
UNION ALL
SELECT CAST('DISTRIBUTION_AT_FLOOR' AS varchar(30)), CAST(dp.{{QbPublication.publisherId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), CAST(count(*) AS numeric)
FROM {{QbPublication}} dp
JOIN {{QbPublicationCard}} dc ON dc.{{QbPublicationCard.publicationId}} = dp.{{QbPublication.publicationId}}
WHERE dp.{{QbPublication.distributionAtFloor}}
  AND dp.{{QbPublication.publisherKind}} = 'SPONSOR'
  AND dc.{{QbPublicationCard.status}} <> 'ENDED'
GROUP BY dp.{{QbPublication.publisherId}}
