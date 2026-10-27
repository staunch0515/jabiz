/*---
id: qb.sponsor.staff
description: "The sponsor's staff (Q39): the takers they named to hand out prizes at the counter, newest first"
entities: [QbSponsorStaff, QbProfile]
datasets:
  QbSponsorStaff: urn:jabiz:dataset:sponsor:QbSponsorStaff
  QbProfile: urn:jabiz:dataset:default:QbProfile
results:
  userId:      { from: QbSponsorStaff.userId }
  displayName: { from: QbProfile.displayName }
  addedAt:     { from: QbSponsorStaff.addedAt }
list:
  sorts:   [addedAt]
  defaultSort: { field: addedAt, asc: false }
  key: [userId]
permissions: [qb.publication.write]
---*/
-- The name the staff member goes by in the App (none if their profile is gone).
SELECT
    s.{{QbSponsorStaff.userId}}  AS userId,
    p.{{QbProfile.displayName}}  AS displayName,
    s.{{QbSponsorStaff.addedAt}} AS addedAt
FROM {{QbSponsorStaff}} s
LEFT JOIN {{QbProfile}} p ON p.{{QbProfile.userId}} = s.{{QbSponsorStaff.userId}}
