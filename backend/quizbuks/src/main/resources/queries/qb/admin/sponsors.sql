/*---
id: qb.admin.sponsors
description: Sponsor onboarding (P-09) with the sponsor's name and address, newest application first
entities: [QbSponsor, SecUser]
datasets:
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
results:
  onboardingId:      { from: QbSponsor.onboardingId }
  userId:            { from: QbSponsor.userId }
  company:           { from: QbSponsor.company }
  contactName:       { from: QbSponsor.contactName }
  email:             { from: SecUser.email }
  reviewStatus:      { from: QbSponsor.reviewStatus }
  submittedAt:       { from: QbSponsor.submittedAt }
  approvedAt:        { from: QbSponsor.approvedAt }
  rejectReason:      { from: QbSponsor.rejectReason }
  approvalRequestId: { from: QbSponsor.approvalRequestId }
list:
  filters: [reviewStatus, submittedAt]
  sorts:   [submittedAt, approvedAt, company]
  defaultSort: { field: submittedAt, asc: false }
  key: [onboardingId]
permissions: [qb.admin.users.read]
---*/
-- Reviewing is the platform's approval panel (/tasks); this is the queue to look things up in.
SELECT
    s.{{QbSponsor.onboardingId}}      AS onboardingId,
    s.{{QbSponsor.userId}}            AS userId,
    s.{{QbSponsor.company}}           AS company,
    s.{{QbSponsor.contactName}}       AS contactName,
    u.{{SecUser.email}}               AS email,
    s.{{QbSponsor.reviewStatus}}      AS reviewStatus,
    s.{{QbSponsor.submittedAt}}       AS submittedAt,
    s.{{QbSponsor.approvedAt}}        AS approvedAt,
    s.{{QbSponsor.rejectReason}}      AS rejectReason,
    s.{{QbSponsor.approvalRequestId}} AS approvalRequestId
FROM {{QbSponsor}} s
LEFT JOIN {{SecUser}} u ON CAST(u.{{SecUser.userId}} AS text) = s.{{QbSponsor.userId}}
