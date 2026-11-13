/*---
id: qb.system.recon-exposure
description: Whether the exposure fee agrees with the ledger and its settlements, by sponsor (F-M8) - the deposits (2180, purpose EXPOSURE) with what the open prepayments hold, settlements beyond what was bought, months and final rows not settled, prices outside the range, a price without a purchase or a purchase without its price, and (a note) running publications of another's video that bought nothing (the daily reconciliation; differences, one for attention and one note)
entities: [LedgerEntry, LedgerAccount, QbPrepayment, QbPublication, QbPublicationCard, QbExposureSettlement, QbExposurePrice, SysParam]
datasets:
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbExposureSettlement: urn:jabiz:dataset:default:QbExposureSettlement
  QbExposurePrice: urn:jabiz:dataset:default:QbExposurePrice
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at: { like: QbPublication.endedAt, description: "The run's time (none: the unsettled months and the missing final rows are not checked)" }
  zone: { kind: { type: text, maxLength: 64 }, description: "The fiscal year's zone, whose months the exposure fee is settled by (none: the unsettled months are not checked)" }
  unsettledHours: { kind: { type: numeric, precision: 3, scale: 0 }, description: "qb.exposure.unsettled-after-hours (none: the unsettled months are not checked)" }
  missingHours: { kind: { type: numeric, precision: 3, scale: 0 }, description: "qb.exposure.final-missing-after-hours (none: the missing final rows are not checked)" }
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
-- EXPOSURE_DEPOSITS (F-M8): by sponsor and publication (the reference), 2180 on the lines of the purpose EXPOSURE
--   (dimension 3) against what the publication's open prepaid EXPOSURE prepayment holds.
-- EXPOSURE_SETTLED_OVER (F-M8): by sponsor and publication, the exposures its settlements counted in all against the
--   exposures bought: expected what was bought, actual what was counted, when more.
-- EXPOSURE_UNSETTLED (F-M8): by sponsor and month, as DISTRIBUTION_UNSETTLED (the same due condition as
--   qb.system.exposure-due and the invoices' readiness), with qb.exposure.unsettled-after-hours.
-- EXPOSURE_FINAL_MISSING (F-M8): by sponsor, ended publications without a final row qb.exposure.final-missing-after-hours
--   after their end.
-- EXPOSURE_PRICE_RANGE (F-M8, for attention): by operator (sponsorId holds it), a price outside the headquarters' range as
--   it is now (a bound not set does not bound).
-- EXPOSURE_UNIT_MISMATCH (F-M8): by sponsor, a sponsor's publication with a price frozen and nothing bought, or something
--   bought and no price frozen (the purchase and the price are written together).
-- EXPOSURE_UNPURCHASED (F-M8, a note): by sponsor, running publications of another sponsor's video that bought nothing
--   (submitted before the switch was on, or whose operator had no price).
WITH exposure_ledger AS (
    SELECT e.{{LedgerEntry.dimension1}} AS r_party, e.{{LedgerEntry.dimension2}} AS r_publication,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2180' AND e.{{LedgerEntry.dimension3}} = 'EXPOSURE'
    GROUP BY e.{{LedgerEntry.dimension1}}, e.{{LedgerEntry.dimension2}}
),
exposure_held AS (
    SELECT p.{{QbPrepayment.sponsorId}} AS r_party, CAST(p.{{QbPrepayment.publicationId}} AS text) AS r_publication,
           sum(p.{{QbPrepayment.remaining}}) AS r_remaining
    FROM {{QbPrepayment}} p
    WHERE p.{{QbPrepayment.status}} = 'OPEN' AND p.{{QbPrepayment.mode}} = 'PREPAID'
      AND p.{{QbPrepayment.purpose}} = 'EXPOSURE'
    GROUP BY p.{{QbPrepayment.sponsorId}}, p.{{QbPrepayment.publicationId}}
),
exposure_params AS (
    -- The two hours are QbParams' values handed in by the reconciliation (no default here); either missing, its check
    -- is not made (SetupParamsCheck reports the missing parameter). The range as it is published now.
    SELECT CAST(:unsettledHours AS int) AS unsettled_hours,
           CAST(:missingHours AS int) AS missing_hours,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.exposure.unit.min' THEN p.{{SysParam.value}} END) AS unit_min,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.exposure.unit.max' THEN p.{{SysParam.value}} END) AS unit_max
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.exposure.unit.min', 'qb.exposure.unit.max')
),
exposure_month AS (
    -- The month before :at in :zone: its first instant, the next month's, and its name.
    SELECT date_trunc('month', CAST(:at AS timestamptz) AT TIME ZONE CAST(:zone AS text))
               AT TIME ZONE CAST(:zone AS text) AS m_end
    WHERE CAST(:at AS timestamptz) IS NOT NULL AND CAST(:zone AS text) IS NOT NULL
)
SELECT CAST('EXPOSURE_DEPOSITS' AS varchar(30)) AS checkCode, CAST(coalesce(xh.r_party, xl.r_party) AS varchar(128)) AS sponsorId,
       CAST(coalesce(xh.r_publication, xl.r_publication) AS varchar(128)) AS reference,
       coalesce(xh.r_remaining, 0) AS expected, coalesce(xl.r_balance, 0) AS actual
FROM exposure_held xh
FULL OUTER JOIN exposure_ledger xl ON xl.r_party = xh.r_party AND xl.r_publication = xh.r_publication
WHERE coalesce(xh.r_remaining, 0) <> coalesce(xl.r_balance, 0)
UNION ALL
SELECT CAST('EXPOSURE_SETTLED_OVER' AS varchar(30)), CAST(xp.{{QbPublication.publisherId}} AS varchar(128)),
       CAST(xp.{{QbPublication.publicationId}} AS varchar(128)),
       CAST(xp.{{QbPublication.exposurePurchased}} AS numeric), CAST(sum(xs.{{QbExposureSettlement.counted}}) AS numeric)
FROM {{QbPublication}} xp
JOIN {{QbExposureSettlement}} xs ON xs.{{QbExposureSettlement.publicationId}} = CAST(xp.{{QbPublication.publicationId}} AS text)
WHERE xp.{{QbPublication.exposurePurchased}} IS NOT NULL
GROUP BY xp.{{QbPublication.publisherId}}, xp.{{QbPublication.publicationId}}, xp.{{QbPublication.exposurePurchased}}
HAVING sum(xs.{{QbExposureSettlement.counted}}) > xp.{{QbPublication.exposurePurchased}}
UNION ALL
SELECT CAST('EXPOSURE_UNSETTLED' AS varchar(30)), CAST(xp.{{QbPublication.publisherId}} AS varchar(128)),
       CAST(to_char(mo.r_month, 'YYYY-MM') AS varchar(128)), CAST(0 AS numeric), CAST(count(*) AS numeric)
FROM {{QbPublication}} xp
JOIN {{QbPublicationCard}} xc ON xc.{{QbPublicationCard.publicationId}} = xp.{{QbPublication.publicationId}}
CROSS JOIN exposure_month xm
CROSS JOIN exposure_params xpar
-- Every month before this one, from the month it went on in to the month it ended in (or the last month), each month
-- without its row or a final one, once qb.exposure.unsettled-after-hours has passed since that month's end.
CROSS JOIN LATERAL generate_series(
    -- When it went on: greatest(its start, its approval); the same expression in qb.system.exposure-due and qb.system.fee-invoices-due (DistributionDueConditionTest).
    date_trunc('month', greatest(xc.{{QbPublicationCard.startAt}}, xp.{{QbPublication.approvedAt}}) AT TIME ZONE CAST(:zone AS text)),
    date_trunc('month', least(coalesce(xc.{{QbPublicationCard.endedAt}}, xm.m_end - interval '1 second'),
                              xm.m_end - interval '1 second') AT TIME ZONE CAST(:zone AS text)),
    interval '1 month') AS mo(r_month)
WHERE xp.{{QbPublication.exposureUnit}} IS NOT NULL
  AND xp.{{QbPublication.exposurePurchased}} IS NOT NULL
  AND xp.{{QbPublication.publisherKind}} = 'SPONSOR'
  AND CAST(:at AS timestamptz) >= (mo.r_month + interval '1 month') AT TIME ZONE CAST(:zone AS text)
                                  + make_interval(hours => xpar.unsettled_hours)
  AND NOT EXISTS (SELECT 1 FROM {{QbExposureSettlement}} xs
                  WHERE xs.{{QbExposureSettlement.publicationId}} = CAST(xp.{{QbPublication.publicationId}} AS text)
                    AND (xs.{{QbExposureSettlement.final}}
                         OR xs.{{QbExposureSettlement.period}} = to_char(mo.r_month, 'YYYY-MM')))
GROUP BY xp.{{QbPublication.publisherId}}, mo.r_month
UNION ALL
SELECT CAST('EXPOSURE_FINAL_MISSING' AS varchar(30)), CAST(xp.{{QbPublication.publisherId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), CAST(count(*) AS numeric)
FROM {{QbPublication}} xp
JOIN {{QbPublicationCard}} xc ON xc.{{QbPublicationCard.publicationId}} = xp.{{QbPublication.publicationId}}
CROSS JOIN exposure_params xpar
WHERE xp.{{QbPublication.exposureUnit}} IS NOT NULL
  AND xp.{{QbPublication.exposurePurchased}} IS NOT NULL
  AND xp.{{QbPublication.publisherKind}} = 'SPONSOR'
  AND xc.{{QbPublicationCard.status}} = 'ENDED'
  AND CAST(:at AS timestamptz) IS NOT NULL
  AND xc.{{QbPublicationCard.endedAt}} < CAST(:at AS timestamptz) - make_interval(hours => xpar.missing_hours)
  AND NOT EXISTS (SELECT 1 FROM {{QbExposureSettlement}} xs
                  WHERE xs.{{QbExposureSettlement.publicationId}} = CAST(xp.{{QbPublication.publicationId}} AS text)
                    AND xs.{{QbExposureSettlement.final}})
GROUP BY xp.{{QbPublication.publisherId}}
UNION ALL
SELECT CAST('EXPOSURE_PRICE_RANGE' AS varchar(30)), CAST(pr.{{QbExposurePrice.operatorId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), CAST(1 AS numeric)
FROM {{QbExposurePrice}} pr
CROSS JOIN exposure_params xpar
WHERE (xpar.unit_min ~ '^[0-9]+(\.[0-9]+)?$' AND pr.{{QbExposurePrice.unit}} < CAST(xpar.unit_min AS numeric))
   OR (xpar.unit_max ~ '^[0-9]+(\.[0-9]+)?$' AND pr.{{QbExposurePrice.unit}} > CAST(xpar.unit_max AS numeric))
UNION ALL
SELECT CAST('EXPOSURE_UNIT_MISMATCH' AS varchar(30)), CAST(xp.{{QbPublication.publisherId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), CAST(count(*) AS numeric)
FROM {{QbPublication}} xp
WHERE xp.{{QbPublication.publisherKind}} = 'SPONSOR'
  AND ((xp.{{QbPublication.exposureUnit}} IS NULL) <> (xp.{{QbPublication.exposurePurchased}} IS NULL))
GROUP BY xp.{{QbPublication.publisherId}}
UNION ALL
SELECT CAST('EXPOSURE_UNPURCHASED' AS varchar(30)), CAST(xp.{{QbPublication.publisherId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), CAST(count(*) AS numeric)
FROM {{QbPublication}} xp
JOIN {{QbPublicationCard}} xc ON xc.{{QbPublicationCard.publicationId}} = xp.{{QbPublication.publicationId}}
WHERE xp.{{QbPublication.publisherKind}} = 'SPONSOR'
  AND xc.{{QbPublicationCard.sharedVideoId}} IS NOT NULL
  AND xc.{{QbPublicationCard.videoProviderId}} <> xp.{{QbPublication.publisherId}}
  AND xp.{{QbPublication.exposurePurchased}} IS NULL
  AND xc.{{QbPublicationCard.status}} IN ('SCHEDULED', 'LIVE', 'PAUSED', 'FUNDS_SHORT')
GROUP BY xp.{{QbPublication.publisherId}}
