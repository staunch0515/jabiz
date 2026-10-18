/*---
id: qb.sponsor.topup-limit
description: >-
  What the sponsor may top up now (Q10b, review M3): the smallest and largest top-up, and while the new sponsors'
  limit applies, the limit in all, what remains of it and until when
entities: [QbTopUp, SysParam]
datasets:
  QbTopUp: urn:jabiz:dataset:sponsor:QbTopUp
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The client's now" }
results:
  min:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  max:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  limited:   { kind: { type: bool } }
  maxTotal:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  remaining: { kind: { type: monetary, currency: JPY, scale: 0 } }
  until:     { kind: { type: temporal, role: EVENT_TIME } }
permissions: [qb.sponsor.finance.read]
---*/
-- One row. The rule is QB_TOPUP_START's (wallet.NewSponsorLimit, which decides; this is for the page): from the first
-- paid top-up for qb.topup.new-sponsor.days days, and before it, the paid top-ups (refunded or not) and the unfinished
-- ones (opening until its checkout job has finished or failed, open until the checkout expires; review L4) count
-- against qb.topup.new-sponsor.max-total. until is null before the first paid top-up (the period starts then). A
-- parameter not set leaves its figures null. The parameters are read at the server's clock (the dataset without time
-- travel, review L9), as QB_TOPUP_START reads them.
WITH params AS (
    SELECT
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.topup.min' THEN p.{{SysParam.value}} END) AS p_min,
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.topup.max' THEN p.{{SysParam.value}} END) AS p_max,
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.topup.new-sponsor.days' THEN p.{{SysParam.value}} END) AS p_days,
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.topup.new-sponsor.max-total' THEN p.{{SysParam.value}} END)
            AS p_total
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.topup.min', 'qb.topup.max', 'qb.topup.new-sponsor.days',
                                      'qb.topup.new-sponsor.max-total')),
checked AS (
    SELECT
        CASE WHEN p_min ~ '^[0-9]{1,18}$' THEN CAST(p_min AS numeric) END AS v_min,
        CASE WHEN p_max ~ '^[0-9]{1,18}$' THEN CAST(p_max AS numeric) END AS v_max,
        CASE WHEN p_days ~ '^[0-9]{1,3}$' THEN CAST(p_days AS int) END AS v_days,
        CASE WHEN p_total ~ '^[0-9]{1,18}$' THEN CAST(p_total AS numeric) END AS v_total
    FROM params),
mine AS (
    SELECT
        min(u.{{QbTopUp.paidAt}}) AS first_paid,
        coalesce(sum(u.{{QbTopUp.amount}}) FILTER (WHERE u.{{QbTopUp.paidAt}} IS NOT NULL), 0) AS paid,
        coalesce(sum(u.{{QbTopUp.amount}}) FILTER (WHERE u.{{QbTopUp.paidAt}} IS NULL AND (
            u.{{QbTopUp.status}} = 'OPENING'
            OR (u.{{QbTopUp.status}} = 'OPEN' AND u.{{QbTopUp.expiresAt}} > CAST(:at AS timestamptz)))), 0) AS unfinished
    FROM {{QbTopUp}} u),
standing AS (
    SELECT c.v_min, c.v_max, c.v_total,
           c.v_days IS NOT NULL AND c.v_total IS NOT NULL
               AND (m.first_paid IS NULL OR CAST(:at AS timestamptz) < m.first_paid + make_interval(days => c.v_days))
               AS limited,
           m.first_paid + make_interval(days => c.v_days) AS ends,
           m.paid + m.unfinished AS used
    FROM checked c CROSS JOIN mine m)
SELECT
    s.v_min AS min,
    s.v_max AS max,
    s.limited AS limited,
    CASE WHEN s.limited THEN s.v_total END AS maxTotal,
    CASE WHEN s.limited THEN greatest(s.v_total - s.used, 0) END AS remaining,
    CASE WHEN s.limited THEN s.ends END AS until
FROM standing s
