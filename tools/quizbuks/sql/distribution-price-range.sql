-- Operators whose distribution price is outside the headquarters' range as it is now: the rows the daily
-- reconciliation reports as DISTRIBUTION_PRICE_RANGE (the same conditions as qb.system.recon-fee-account).
-- Run it before deploying the version that renamed the check (before it, the check was never reported): every row
-- is a difference from the first run on, and the journal export needs the latest run after the month's end to be
-- OK (docs/quizbuks/operations.md §7.2). An empty result is the go-ahead; otherwise bring those prices within the
-- range, or the range around them, first.
--
--   psql "$DATABASE_URL" -f tools/quizbuks/sql/distribution-price-range.sql
--
-- Read-only. A range parameter not set (or not a number) checks nothing, as in the reconciliation.
WITH current_params AS (
    SELECT DISTINCT ON (p.param_id) p.param_key, p.param_value, p.is_deleted
    FROM sys_param_version p
    WHERE p.param_key LIKE 'qb.distribution.%'
      AND p.effect_start_time <= now()
    ORDER BY p.param_id, p.effect_start_time DESC, p.version_no DESC
),
dpar AS (
    SELECT max(CASE WHEN param_key = 'qb.distribution.rate.min' THEN param_value END) AS rate_min,
           max(CASE WHEN param_key = 'qb.distribution.rate.max' THEN param_value END) AS rate_max,
           max(CASE WHEN param_key = 'qb.distribution.unit-floor.min' THEN param_value END) AS floor_min,
           max(CASE WHEN param_key = 'qb.distribution.unit-floor.max' THEN param_value END) AS floor_max,
           max(CASE WHEN param_key = 'qb.distribution.unit-ceiling.max' THEN param_value END) AS ceiling_max,
           max(CASE WHEN param_key = 'qb.distribution.minimum-deposit.max' THEN param_value END) AS deposit_max
    FROM current_params
    WHERE NOT is_deleted
)
SELECT pr.operator_id, pr.rate, pr.unit_floor, pr.unit_ceiling, pr.minimum_deposit,
       dpar.rate_min, dpar.rate_max, dpar.floor_min, dpar.floor_max, dpar.ceiling_max, dpar.deposit_max
FROM qb_distribution_price pr
CROSS JOIN dpar
WHERE (dpar.rate_min ~ '^[0-9]+(\.[0-9]+)?$' AND pr.rate < CAST(dpar.rate_min AS numeric))
   OR (dpar.rate_max ~ '^[0-9]+(\.[0-9]+)?$' AND pr.rate > CAST(dpar.rate_max AS numeric))
   OR (dpar.floor_min ~ '^[0-9]+(\.[0-9]+)?$' AND pr.unit_floor < CAST(dpar.floor_min AS numeric))
   OR (dpar.floor_max ~ '^[0-9]+(\.[0-9]+)?$' AND pr.unit_floor > CAST(dpar.floor_max AS numeric))
   OR (dpar.ceiling_max ~ '^[0-9]+(\.[0-9]+)?$' AND pr.unit_ceiling > CAST(dpar.ceiling_max AS numeric))
   OR (dpar.deposit_max ~ '^[0-9]+(\.[0-9]+)?$' AND pr.minimum_deposit > CAST(dpar.deposit_max AS numeric))
ORDER BY pr.operator_id;
