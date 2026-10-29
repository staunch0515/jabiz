/*---
id: qb.admin.operator-reviews
description: "The reviews operators decided (Q48a, D-Q48-5): every decision a member of an operator made on a sponsor's application or a publication, with headquarters' spot check, newest first"
entities: [ApprovalDecision, SysApprovalRequest, QbOperatorMember, QbOperator, QbReviewSpotCheck, QbSponsor, QbPublication]
datasets:
  ApprovalDecision: urn:jabiz:dataset:platform:ApprovalDecision
  SysApprovalRequest: urn:jabiz:dataset:platform:SysApprovalRequest
  QbOperatorMember: urn:jabiz:dataset:default:QbOperatorMember
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbReviewSpotCheck: urn:jabiz:dataset:default:QbReviewSpotCheck
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbPublication: urn:jabiz:dataset:default:QbPublication
params:
  operatorId: { like: QbOperator.operatorId, description: Only this operator's decisions }
  unchecked:  { kind: { type: bool }, description: Only the decisions headquarters has not checked yet }
results:
  decisionId:   { from: ApprovalDecision.decisionId }
  decidedAt:    { from: ApprovalDecision.decidedTime }
  decision:     { from: ApprovalDecision.decision }
  reason:       { from: ApprovalDecision.reason }
  approverId:   { from: ApprovalDecision.approverId }
  subject:      { from: SysApprovalRequest.subject }
  entityId:     { from: SysApprovalRequest.entityId }
  sponsorId:    { kind: { type: text, maxLength: 128 } }
  title:        { kind: { type: text, maxLength: 200 } }
  operatorId:   { from: QbOperator.operatorId }
  operatorName: { from: QbOperator.name }
  checkResult:  { from: QbReviewSpotCheck.result }
  checkNote:    { from: QbReviewSpotCheck.note }
  checkedAt:    { from: QbReviewSpotCheck.checkedAt }
  checkedBy:    { from: QbReviewSpotCheck.checkedBy }
list:
  filters: [decision, subject, operatorId, checkResult, decidedAt]
  sorts:   [decidedAt, decision]
  defaultSort: { field: decidedAt, asc: false }
  key: [decisionId]
permissions: [qb.admin.spot-check]
---*/
-- A decision is an operator's when its approver was a member of that operator at the time (removed members' rows
-- stay, so earlier decisions keep their operator). What it was about: the onboarding's company (the request names the
-- onboarding) or the publication's name, and whose (the sponsor's user id, for the user page).
SELECT
    d.{{ApprovalDecision.decisionId}}  AS decisionId,
    d.{{ApprovalDecision.decidedTime}} AS decidedAt,
    d.{{ApprovalDecision.decision}}    AS decision,
    d.{{ApprovalDecision.reason}}      AS reason,
    d.{{ApprovalDecision.approverId}}  AS approverId,
    r.{{SysApprovalRequest.subject}}   AS subject,
    r.{{SysApprovalRequest.entityId}}  AS entityId,
    coalesce(s.{{QbSponsor.userId}}, p.{{QbPublication.publisherId}}) AS sponsorId,
    coalesce(s.{{QbSponsor.company}}, p.{{QbPublication.name}})       AS title,
    o.{{QbOperator.operatorId}}        AS operatorId,
    o.{{QbOperator.name}}              AS operatorName,
    c.{{QbReviewSpotCheck.result}}     AS checkResult,
    c.{{QbReviewSpotCheck.note}}       AS checkNote,
    c.{{QbReviewSpotCheck.checkedAt}}  AS checkedAt,
    c.{{QbReviewSpotCheck.checkedBy}}  AS checkedBy
FROM {{ApprovalDecision}} d
JOIN {{SysApprovalRequest}} r ON r.{{SysApprovalRequest.requestId}} = d.{{ApprovalDecision.requestId}}
JOIN {{QbOperatorMember}} m ON CAST(m.{{QbOperatorMember.userId}} AS text) = d.{{ApprovalDecision.approverId}}
    AND m.{{QbOperatorMember.addedAt}} <= d.{{ApprovalDecision.decidedTime}}
    AND (m.{{QbOperatorMember.removedAt}} IS NULL OR m.{{QbOperatorMember.removedAt}} > d.{{ApprovalDecision.decidedTime}})
JOIN {{QbOperator}} o ON o.{{QbOperator.operatorId}} = m.{{QbOperatorMember.operatorId}}
LEFT JOIN {{QbReviewSpotCheck}} c ON c.{{QbReviewSpotCheck.decisionId}} = d.{{ApprovalDecision.decisionId}}
LEFT JOIN {{QbSponsor}} s ON r.{{SysApprovalRequest.subject}} = 'qb.sponsor'
    AND CAST(s.{{QbSponsor.onboardingId}} AS text) = r.{{SysApprovalRequest.entityId}}
LEFT JOIN {{QbPublication}} p ON r.{{SysApprovalRequest.subject}} = 'qb.publication'
    AND CAST(p.{{QbPublication.publicationId}} AS text) = r.{{SysApprovalRequest.entityId}}
WHERE r.{{SysApprovalRequest.subject}} IN ('qb.sponsor', 'qb.publication')
  AND (CAST(:operatorId AS text) IS NULL OR o.{{QbOperator.operatorId}} = CAST(:operatorId AS text))
  AND (CAST(:unchecked AS boolean) IS NOT TRUE OR c.{{QbReviewSpotCheck.checkId}} IS NULL)
