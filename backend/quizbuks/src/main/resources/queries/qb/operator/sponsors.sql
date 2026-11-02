/*---
id: qb.operator.sponsors
description: "The sponsors an operator looks after (Q48a; its own only since Q59c-2, the dataset of the sponsors being limited to them), with their review, ban and running publications"
entities: [QbSponsor, SecUser, QbSponsorOperator, QbOperator, QbOperatorMember, QbUserBan, QbPublicationCard]
datasets:
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbUserBan: urn:jabiz:dataset:default:QbUserBan
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
params:
  q:      { kind: { type: text, maxLength: 100 }, description: "Only sponsors whose company, contact name or address contains this text" }
  userId: { like: QbSponsor.userId, description: Only this sponsor (the operator's page of one sponsor) }
results:
  onboardingId:      { from: QbSponsor.onboardingId }
  userId:            { from: QbSponsor.userId }
  company:           { from: QbSponsor.company }
  contactName:       { from: QbSponsor.contactName }
  intro:             { from: QbSponsor.intro }
  introTranslations: { from: QbSponsor.introTranslations }
  avatar:            { from: QbSponsor.avatar }
  termsAcceptedAt:   { from: QbSponsor.termsAcceptedAt }
  email:             { from: SecUser.email }
  emailVerified:     { kind: { type: bool } }
  reviewStatus:      { from: QbSponsor.reviewStatus }
  submittedAt:       { from: QbSponsor.submittedAt }
  approvedAt:        { from: QbSponsor.approvedAt }
  rejectReason:      { from: QbSponsor.rejectReason }
  approvalRequestId: { from: QbSponsor.approvalRequestId }
  operatorId:        { from: QbSponsorOperator.operatorId }
  operatorName:      { from: QbOperator.name }
  mine:              { kind: { type: bool } }
  banned:            { kind: { type: bool } }
  bannedByOperator:  { kind: { type: bool } }
  bannedAt:          { from: QbUserBan.bannedAt }
  runningPublications: { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  filters: [reviewStatus, mine, banned, submittedAt]
  sorts:   [submittedAt, approvedAt, company]
  defaultSort: { field: submittedAt, asc: false }
  key: [onboardingId]
permissions: [qb.operator.use]
---*/
-- The sponsors' business contacts, not the takers: an operator sees no taker's profile (D-Q48-4). "mine": assigned to
-- the caller's operator (the membership dataset is the caller's own; a sponsor without an assignment is
-- headquarters'). The ban's reason is internal and stays out; bannedByOperator says whether the caller's operator
-- banned them, so the page offers the lift only then. Running: scheduled, live, underfunded or paused. The
-- onboarding's content (contact, company, introduction, picture, terms) is what a review of the application is bound
-- to: the operator's review page shows it as headquarters' does.
SELECT
    s.{{QbSponsor.onboardingId}}      AS onboardingId,
    s.{{QbSponsor.userId}}            AS userId,
    s.{{QbSponsor.company}}           AS company,
    s.{{QbSponsor.contactName}}       AS contactName,
    s.{{QbSponsor.intro}}             AS intro,
    s.{{QbSponsor.introTranslations}} AS introTranslations,
    s.{{QbSponsor.avatar}}            AS avatar,
    s.{{QbSponsor.termsAcceptedAt}}   AS termsAcceptedAt,
    u.{{SecUser.email}}               AS email,
    coalesce(lower(u.{{SecUser.verifiedEmail}}) = lower(u.{{SecUser.email}}), false) AS emailVerified,
    s.{{QbSponsor.reviewStatus}}      AS reviewStatus,
    s.{{QbSponsor.submittedAt}}       AS submittedAt,
    s.{{QbSponsor.approvedAt}}        AS approvedAt,
    s.{{QbSponsor.rejectReason}}      AS rejectReason,
    s.{{QbSponsor.approvalRequestId}} AS approvalRequestId,
    a.{{QbSponsorOperator.operatorId}} AS operatorId,
    o.{{QbOperator.name}}             AS operatorName,
    coalesce(a.{{QbSponsorOperator.operatorId}} = me.{{QbOperatorMember.operatorId}}, false) AS mine,
    coalesce(b.{{QbUserBan.banned}}, false) AS banned,
    coalesce(b.{{QbUserBan.banned}} AND b.{{QbUserBan.operatorId}} = me.{{QbOperatorMember.operatorId}}, false)
                                      AS bannedByOperator,
    CASE WHEN b.{{QbUserBan.banned}} THEN b.{{QbUserBan.bannedAt}} END AS bannedAt,
    (SELECT count(*) FROM {{QbPublicationCard}} c
      WHERE c.{{QbPublicationCard.publisherKind}} = 'SPONSOR'
        AND c.{{QbPublicationCard.publisherId}} = s.{{QbSponsor.userId}}
        AND c.{{QbPublicationCard.status}} IN ('SCHEDULED', 'LIVE', 'FUNDS_SHORT', 'PAUSED')) AS runningPublications
FROM {{QbSponsor}} s
LEFT JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
LEFT JOIN {{SecUser}} u ON CAST(u.{{SecUser.userId}} AS text) = s.{{QbSponsor.userId}}
LEFT JOIN {{QbSponsorOperator}} a ON a.{{QbSponsorOperator.userId}} = s.{{QbSponsor.userId}}
LEFT JOIN {{QbOperator}} o ON o.{{QbOperator.operatorId}} = a.{{QbSponsorOperator.operatorId}}
LEFT JOIN {{QbUserBan}} b ON CAST(b.{{QbUserBan.userId}} AS text) = s.{{QbSponsor.userId}}
WHERE (CAST(:q AS text) IS NULL
       OR strpos(lower(s.{{QbSponsor.company}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(s.{{QbSponsor.contactName}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(u.{{SecUser.email}}, '')), lower(CAST(:q AS text))) > 0)
  AND (CAST(:userId AS text) IS NULL OR s.{{QbSponsor.userId}} = CAST(:userId AS text))
