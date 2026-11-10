/*---
id: qb.system.fee-credit-certified-due
description: The verified Sponsors whose onboarding platform fee Kudos are to be topped up (the items of the daily job qb.fee-credit-certified-due, F-M3) - verified, granted an onboarding lot below the certified amount, no top-up yet
entities: [QbSponsorVerification, QbFeeCreditGrant, SysParam]
datasets:
  QbSponsorVerification: urn:jabiz:dataset:default:QbSponsorVerification
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  sponsorId: { from: QbSponsorVerification.sponsorId }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.fee.system]
---*/
-- The same rule as fee.FeeCreditRules#certifiedTopUp, which the item runs again under the Sponsor's wallet lock (with
-- the onboarding's approval and the ban): while the Sponsor is banned the item grants nothing and the Sponsor is listed
-- again. Nobody while the grants' switch is off, or the certified amount is not whole yen (then nothing is due: the
-- same as the item, not an error). What is due tops up to the certified amount recorded on the onboarding lot when it
-- was granted, no more than the parameter now (the parameter now for a lot granted before it was recorded): raising
-- the parameter selects nobody granted before it.
WITH switch AS (
    SELECT bool_or(p.{{SysParam.value}} = 'true') AS r_enabled
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.fee-credit.grant.enabled'
),
certified AS (
    SELECT max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,13}$' THEN CAST(p.{{SysParam.value}} AS numeric) END) AS r_amount
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.fee-credit.grant.certified'
)
SELECT DISTINCT v.{{QbSponsorVerification.sponsorId}} AS sponsorId
FROM {{QbSponsorVerification}} v
JOIN {{QbFeeCreditGrant}} o ON o.{{QbFeeCreditGrant.sponsorId}} = v.{{QbSponsorVerification.sponsorId}}
    AND o.{{QbFeeCreditGrant.sourceKey}} = 'onboarding'
CROSS JOIN switch
CROSS JOIN certified c
WHERE v.{{QbSponsorVerification.status}} = 'APPROVED'
  AND switch.r_enabled IS TRUE
  AND c.r_amount IS NOT NULL
  AND o.{{QbFeeCreditGrant.amount}} < coalesce(least(o.{{QbFeeCreditGrant.certifiedAmount}}, c.r_amount), c.r_amount)
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeCreditGrant}} t
                  WHERE t.{{QbFeeCreditGrant.sponsorId}} = v.{{QbSponsorVerification.sponsorId}}
                    AND t.{{QbFeeCreditGrant.sourceKey}} = 'certified')
