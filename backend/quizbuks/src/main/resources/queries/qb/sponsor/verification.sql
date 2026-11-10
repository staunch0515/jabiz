/*---
id: qb.sponsor.verification
description: >-
  F-M3: the Sponsor's company verification as their page shows it - their latest submission (none: never submitted)
  with when its link stops working, whether they are verified now, and the onboarding platform fee Kudos it tops up
  (the onboarding granted, the certified amount, whether topped up already); always one row
entities: [QbSponsorVerification, QbFeeCreditGrant, SysParam]
datasets:
  QbSponsorVerification: urn:jabiz:dataset:me:QbSponsorVerification
  QbFeeCreditGrant: urn:jabiz:dataset:sponsor:QbFeeCreditGrant
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  verificationId:   { from: QbSponsorVerification.verificationId }
  companyName:      { from: QbSponsorVerification.companyName }
  domain:           { from: QbSponsorVerification.domain }
  method:           { from: QbSponsorVerification.method }
  confirmAddress:   { from: QbSponsorVerification.confirmAddress }
  status:           { from: QbSponsorVerification.status }
  linkSentAt:       { from: QbSponsorVerification.linkSentAt }
  linkExpiresAt:    { from: QbSponsorVerification.linkSentAt }
  submittedAt:      { from: QbSponsorVerification.submittedAt }
  verifiedAt:       { from: QbSponsorVerification.verifiedAt }
  rejectReason:     { from: QbSponsorVerification.rejectReason }
  revokeReason:     { from: QbSponsorVerification.revokeReason }
  endedAt:          { from: QbSponsorVerification.endedAt }
  verified:         { kind: { type: bool } }
  onboardingAmount: { from: QbFeeCreditGrant.amount }
  certifiedAmount:  { from: QbFeeCreditGrant.amount }
  toppedUp:         { kind: { type: bool } }
list:
  key: [verificationId]
permissions: [qb.sponsor.verification]
---*/
-- The submission the page is about: the one that stands (in progress or in effect; one at most, the index makes it
-- so), else the latest. verified is the one definition (verification.VerificationRules#verified): a submission APPROVED.
-- linkExpiresAt follows qb.sponsor-verification.link-hours as the confirmation checks it (now, not when sent).
WITH latest AS (
    SELECT v.*
    FROM {{QbSponsorVerification}} v
    ORDER BY (v.{{QbSponsorVerification.status}} IN ('LINK_SENT', 'REVIEW', 'APPROVED')) DESC,
             v.{{QbSponsorVerification.submittedAt}} DESC, v.{{QbSponsorVerification.verificationId}} DESC
    LIMIT 1
),
hours AS (
    SELECT CAST(max(p.{{SysParam.value}}) AS int) AS h FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.sponsor-verification.link-hours'
)
SELECT
    CAST(l.{{QbSponsorVerification.verificationId}} AS text) AS verificationId,
    l.{{QbSponsorVerification.companyName}} AS companyName,
    l.{{QbSponsorVerification.domain}} AS domain,
    l.{{QbSponsorVerification.method}} AS method,
    l.{{QbSponsorVerification.confirmAddress}} AS confirmAddress,
    l.{{QbSponsorVerification.status}} AS status,
    l.{{QbSponsorVerification.linkSentAt}} AS linkSentAt,
    l.{{QbSponsorVerification.linkSentAt}} + make_interval(hours => least(greatest((SELECT h FROM hours), 1), 168))
        AS linkExpiresAt,
    l.{{QbSponsorVerification.submittedAt}} AS submittedAt,
    l.{{QbSponsorVerification.verifiedAt}} AS verifiedAt,
    l.{{QbSponsorVerification.rejectReason}} AS rejectReason,
    l.{{QbSponsorVerification.revokeReason}} AS revokeReason,
    l.{{QbSponsorVerification.endedAt}} AS endedAt,
    EXISTS (SELECT 1 FROM {{QbSponsorVerification}} a
            WHERE a.{{QbSponsorVerification.status}} = 'APPROVED') AS verified,
    (SELECT g.{{QbFeeCreditGrant.amount}} FROM {{QbFeeCreditGrant}} g
     WHERE g.{{QbFeeCreditGrant.sourceKey}} = 'onboarding') AS onboardingAmount,
    (SELECT CAST(max(p.{{SysParam.value}}) AS numeric) FROM {{SysParam}} p
     WHERE p.{{SysParam.paramKey}} = 'qb.fee-credit.grant.certified') AS certifiedAmount,
    EXISTS (SELECT 1 FROM {{QbFeeCreditGrant}} t WHERE t.{{QbFeeCreditGrant.sourceKey}} = 'certified') AS toppedUp
FROM (SELECT 1 AS one) one
LEFT JOIN latest l ON true
