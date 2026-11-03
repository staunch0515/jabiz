/*---
id: qb.system.fee-monthly-due
description: The approved sponsors without the platform's monthly fee of a month yet (the items of the job qb.fee-monthly, Q87); none while the fee is 0
entities: [QbSponsor, QbFeeCharge, SysParam]
datasets:
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  period: { like: QbFeeCharge.period, required: true, description: "The month (yyyy-MM, Japan's)" }
results:
  sponsorId: { from: QbSponsor.userId }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.fee.system]
---*/
-- The item reads again under the sponsor's wallet lock and charges nothing when the month's fee is there meanwhile.
-- A fee of 0 (the declared value) means no monthly fee: nobody is due.
SELECT s.{{QbSponsor.userId}} AS sponsorId
FROM {{QbSponsor}} s
WHERE s.{{QbSponsor.reviewStatus}} = 'APPROVED'
  AND EXISTS (SELECT 1 FROM {{SysParam}} p
              WHERE p.{{SysParam.paramKey}} = 'qb.fee.monthly'
                AND p.{{SysParam.value}} ~ '^[0-9]{1,18}$' AND CAST(p.{{SysParam.value}} AS numeric) > 0)
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeCharge}} c
                  WHERE c.{{QbFeeCharge.sponsorId}} = s.{{QbSponsor.userId}}
                    AND c.{{QbFeeCharge.kind}} = 'MONTHLY'
                    AND c.{{QbFeeCharge.period}} = :period)
