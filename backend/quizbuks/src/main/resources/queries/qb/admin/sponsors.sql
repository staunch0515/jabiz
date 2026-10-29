/*---
id: qb.admin.sponsors
description: Sponsor onboarding (P-09) with the sponsor's name and address, newest application first
entities: [QbSponsor, SecUser, QbSponsorRewardMethods, QbSponsorOperator, QbOperator]
datasets:
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorRewardMethods: urn:jabiz:dataset:default:QbSponsorRewardMethods
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperator: urn:jabiz:dataset:default:QbOperator
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
  rewardMethods:     { from: QbSponsorRewardMethods.methods }
  operatorId:        { from: QbSponsorOperator.operatorId }
  operatorName:      { from: QbOperator.name }
list:
  filters: [reviewStatus, submittedAt, operatorId]
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
    s.{{QbSponsor.approvalRequestId}} AS approvalRequestId,
    coalesce(rm.{{QbSponsorRewardMethods.methods}}, 'PLATFORM') AS rewardMethods,
    a.{{QbSponsorOperator.operatorId}} AS operatorId,
    o.{{QbOperator.name}}             AS operatorName
FROM {{QbSponsor}} s
LEFT JOIN {{SecUser}} u ON CAST(u.{{SecUser.userId}} AS text) = s.{{QbSponsor.userId}}
-- Q22: the reward methods an administrator allowed; PLATFORM alone without a setting.
LEFT JOIN {{QbSponsorRewardMethods}} rm ON rm.{{QbSponsorRewardMethods.userId}} = s.{{QbSponsor.userId}}
-- Q48a: the sponsor's operator; none is headquarters ("direct").
LEFT JOIN {{QbSponsorOperator}} a ON a.{{QbSponsorOperator.userId}} = s.{{QbSponsor.userId}}
LEFT JOIN {{QbOperator}} o ON o.{{QbOperator.operatorId}} = a.{{QbSponsorOperator.operatorId}}
