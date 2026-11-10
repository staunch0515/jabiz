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
-- the switch, the onboarding's approval and the ban): while the switch is off, or the Sponsor is banned, the item
-- grants nothing and the Sponsor is listed again.
SELECT DISTINCT v.{{QbSponsorVerification.sponsorId}} AS sponsorId
FROM {{QbSponsorVerification}} v
JOIN {{QbFeeCreditGrant}} o ON o.{{QbFeeCreditGrant.sponsorId}} = v.{{QbSponsorVerification.sponsorId}}
    AND o.{{QbFeeCreditGrant.sourceKey}} = 'onboarding'
JOIN {{SysParam}} c ON c.{{SysParam.paramKey}} = 'qb.fee-credit.grant.certified'
WHERE v.{{QbSponsorVerification.status}} = 'APPROVED'
  AND o.{{QbFeeCreditGrant.amount}} < CAST(c.{{SysParam.value}} AS numeric)
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeCreditGrant}} t
                  WHERE t.{{QbFeeCreditGrant.sponsorId}} = v.{{QbSponsorVerification.sponsorId}}
                    AND t.{{QbFeeCreditGrant.sourceKey}} = 'certified')
