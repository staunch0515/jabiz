/*---
id: qb.admin.usage
description: >-
  The sponsors' usage by month (F-C2), for headquarters and the operator looking after the sponsor: for each sponsor,
  month (of the snapshots' days, the deployment's zone) and meter, the days with a snapshot, the average, the peak and
  the month's last value (what charging will be based on later; nothing is charged in the first launch). Bytes are as
  uploaded, without variants
entities: [QbUsageDay, QbSponsor]
datasets:
  QbUsageDay: urn:jabiz:dataset:default:QbUsageDay
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  from:      { kind: { type: date }, required: true, description: The first day of the first month }
  to:        { kind: { type: date }, required: true, description: The last day of the last month }
  sponsorId: { like: QbUsageDay.sponsorId, description: "If given, only this sponsor" }
  meter:     { kind: { type: text, maxLength: 32 }, description: "If given, only this meter (a code of usage.UsageMeters)" }
results:
  sponsorId: { from: QbUsageDay.sponsorId }
  company:   { from: QbSponsor.company }
  month:     { kind: { type: text, maxLength: 7 } }
  meter:     { kind: { type: text, maxLength: 32 } }
  days:      { kind: { type: numeric, precision: 3, scale: 0 } }
  average:   { kind: { type: numeric, precision: 19, scale: 0 } }
  peak:      { kind: { type: numeric, precision: 19, scale: 0 } }
  latest:    { kind: { type: numeric, precision: 19, scale: 0 } }
list:
  filters: [sponsorId, company, month, meter]
  sorts:   [month, company, meter, average, peak]
  defaultSort: { field: month, asc: false }
  key: [sponsorId, month, meter]
permissions: [qb.admin.publication.read]
---*/
-- Both datasets are limited to the operator's sponsors for an operator's session (Q59c-2). A day's row holds every
-- meter in a JSON object (usage.UsageMeters' keys); each meter of it is one value here. The average is over the days
-- that have a snapshot (rounded half up), so a missing day does not lower it; "days" shows how many there were. The
-- company is the onboarding's current one (empty when the record is gone).
WITH d AS (
    SELECT
        u.{{QbUsageDay.sponsorId}} AS s,
        to_char(u.{{QbUsageDay.day}}, 'YYYY-MM') AS mon,
        e.key AS m,
        u.{{QbUsageDay.day}} AS dy,
        CAST(e.value AS numeric) AS v
    FROM {{QbUsageDay}} u
    CROSS JOIN LATERAL jsonb_each_text(CAST(u.{{QbUsageDay.meters}} AS jsonb)) e
    WHERE u.{{QbUsageDay.day}} BETWEEN CAST(:from AS date) AND CAST(:to AS date)
      AND (CAST(:sponsorId AS text) IS NULL OR u.{{QbUsageDay.sponsorId}} = CAST(:sponsorId AS text))
      AND (CAST(:meter AS text) IS NULL OR e.key = CAST(:meter AS text))
      AND e.value ~ '^[0-9]{1,19}$'),
months AS (
    SELECT s, mon, m,
        count(*) AS days,
        round(avg(v)) AS average,
        max(v) AS peak,
        (array_agg(v ORDER BY dy DESC))[1] AS latest
    FROM d
    GROUP BY s, mon, m)
SELECT
    x.s AS sponsorId,
    (SELECT max(p.{{QbSponsor.company}}) FROM {{QbSponsor}} p WHERE p.{{QbSponsor.userId}} = x.s) AS company,
    CAST(x.mon AS varchar(7)) AS month,
    CAST(x.m AS varchar(32)) AS meter,
    CAST(x.days AS numeric(3, 0)) AS days,
    CAST(x.average AS numeric(19, 0)) AS average,
    CAST(x.peak AS numeric(19, 0)) AS peak,
    CAST(x.latest AS numeric(19, 0)) AS latest
FROM months x
