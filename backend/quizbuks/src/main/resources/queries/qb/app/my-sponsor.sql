/*---
id: qb.app.my-sponsor
description: >-
  The caller's own sponsor account as the app shows it (Q68): the company, the picture and where the onboarding
  stands; no row for a taker who is not a sponsor
entities: [QbSponsor]
datasets:
  QbSponsor: urn:jabiz:dataset:sponsor:QbSponsor
results:
  company:      { from: QbSponsor.company }
  contactName:  { from: QbSponsor.contactName }
  avatar:       { from: QbSponsor.avatar }
  reviewStatus: { from: QbSponsor.reviewStatus }
  approvedAt:   { from: QbSponsor.approvedAt }
permissions: [qb.sponsor.app]
---*/
-- The sponsor's dataset is the caller's own onboarding only (its scope: the operator's id), so nobody reads another's.
SELECT
    s.{{QbSponsor.company}}      AS company,
    s.{{QbSponsor.contactName}}  AS contactName,
    s.{{QbSponsor.avatar}}       AS avatar,
    s.{{QbSponsor.reviewStatus}} AS reviewStatus,
    s.{{QbSponsor.approvedAt}}   AS approvedAt
FROM {{QbSponsor}} s
