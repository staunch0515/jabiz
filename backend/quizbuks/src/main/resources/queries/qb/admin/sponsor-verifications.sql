/*---
id: qb.admin.sponsor-verifications
description: >-
  F-M3: the Sponsors' company verifications, newest first (headquarters every Sponsor's; an operator its own
  Sponsors', the default dataset's scope), with the review's request and, for one by the account's address, its
  domain; filtered by status, Sponsor or domain
entities: [QbSponsorVerification]
datasets:
  QbSponsorVerification: urn:jabiz:dataset:default:QbSponsorVerification
params:
  status:    { kind: { type: text, maxLength: 16 }, description: Only this status }
  sponsorId: { kind: { type: text, maxLength: 128 }, description: Only this Sponsor's }
  domain:    { kind: { type: text, maxLength: 253 }, description: Only this domain }
results:
  verificationId:    { from: QbSponsorVerification.verificationId }
  sponsorId:         { from: QbSponsorVerification.sponsorId }
  companyName:       { from: QbSponsorVerification.companyName }
  domain:            { from: QbSponsorVerification.domain }
  method:            { from: QbSponsorVerification.method }
  confirmAddress:    { from: QbSponsorVerification.confirmAddress }
  status:            { from: QbSponsorVerification.status }
  approvalRequestId: { from: QbSponsorVerification.approvalRequestId }
  operatorId:        { from: QbSponsorVerification.operatorId }
  submittedAt:       { from: QbSponsorVerification.submittedAt }
  confirmedAt:       { from: QbSponsorVerification.confirmedAt }
  decidedAt:         { from: QbSponsorVerification.decidedAt }
  verifiedAt:        { from: QbSponsorVerification.verifiedAt }
  rejectReason:      { from: QbSponsorVerification.rejectReason }
  endedAt:           { from: QbSponsorVerification.endedAt }
  revokeReason:      { from: QbSponsorVerification.revokeReason }
  revokedOperatorId: { from: QbSponsorVerification.revokedOperatorId }
list:
  sorts: [submittedAt, verifiedAt, companyName, domain]
  defaultSort: { field: submittedAt, asc: false }
  key: [verificationId]
permissions: [qb.admin.verification.read]
---*/
-- The address the link went to is shown to the reviewers: it is what proves the domain.
SELECT
    CAST(v.{{QbSponsorVerification.verificationId}} AS text) AS verificationId,
    v.{{QbSponsorVerification.sponsorId}} AS sponsorId,
    v.{{QbSponsorVerification.companyName}} AS companyName,
    v.{{QbSponsorVerification.domain}} AS domain,
    v.{{QbSponsorVerification.method}} AS method,
    v.{{QbSponsorVerification.confirmAddress}} AS confirmAddress,
    v.{{QbSponsorVerification.status}} AS status,
    v.{{QbSponsorVerification.approvalRequestId}} AS approvalRequestId,
    v.{{QbSponsorVerification.operatorId}} AS operatorId,
    v.{{QbSponsorVerification.submittedAt}} AS submittedAt,
    v.{{QbSponsorVerification.confirmedAt}} AS confirmedAt,
    v.{{QbSponsorVerification.decidedAt}} AS decidedAt,
    v.{{QbSponsorVerification.verifiedAt}} AS verifiedAt,
    v.{{QbSponsorVerification.rejectReason}} AS rejectReason,
    v.{{QbSponsorVerification.endedAt}} AS endedAt,
    v.{{QbSponsorVerification.revokeReason}} AS revokeReason,
    v.{{QbSponsorVerification.revokedOperatorId}} AS revokedOperatorId
FROM {{QbSponsorVerification}} v
WHERE (CAST(:status AS text) IS NULL OR v.{{QbSponsorVerification.status}} = :status)
  AND (CAST(:sponsorId AS text) IS NULL OR v.{{QbSponsorVerification.sponsorId}} = :sponsorId)
  AND (CAST(:domain AS text) IS NULL OR v.{{QbSponsorVerification.domain}} = lower(:domain))
