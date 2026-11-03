/*---
id: qb.system.fee-monthly-items
description: "Q87b: the approved sponsors not yet charged the month's platform fee (the monthly job's items; none while the fee is 0)"
entities: [QbSponsor, QbFeeCharge, SysParam]
datasets:
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  period: { like: QbFeeCharge.period, required: true, description: "The month (YYYY-MM, Japan)" }
results:
  sponsorId: { from: QbSponsor.userId }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.fee.system]
---*/
SELECT s.{{QbSponsor.userId}} AS sponsorId
FROM {{QbSponsor}} s
WHERE s.{{QbSponsor.reviewStatus}} = 'APPROVED'
  AND EXISTS (SELECT 1 FROM {{SysParam}} p WHERE p.{{SysParam.paramKey}} = 'qb.fee.monthly'
      AND p.{{SysParam.value}} ~ '^[0-9]+$' AND CAST(p.{{SysParam.value}} AS numeric) > 0)
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeCharge}} c WHERE c.{{QbFeeCharge.sponsorId}} = s.{{QbSponsor.userId}}
      AND c.{{QbFeeCharge.kind}} = 'MONTHLY' AND c.{{QbFeeCharge.period}} = :period)
