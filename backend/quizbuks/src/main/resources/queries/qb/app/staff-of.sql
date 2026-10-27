/*---
id: qb.app.staff-of
description: "The sponsor whose staff the taker is on (Q39): none, or one row"
entities: [QbSponsorStaff, QbSponsor]
datasets:
  QbSponsorStaff: urn:jabiz:dataset:taker:QbSponsorStaff
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
results:
  sponsorId:      { from: QbSponsorStaff.sponsorId }
  sponsorCompany: { from: QbSponsor.company }
  addedAt:        { from: QbSponsorStaff.addedAt }
list:
  key: [sponsorId]
permissions: [qb.me]
---*/
SELECT
    s.{{QbSponsorStaff.sponsorId}} AS sponsorId,
    sp.{{QbSponsor.company}}       AS sponsorCompany,
    s.{{QbSponsorStaff.addedAt}}   AS addedAt
FROM {{QbSponsorStaff}} s
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = s.{{QbSponsorStaff.sponsorId}}
