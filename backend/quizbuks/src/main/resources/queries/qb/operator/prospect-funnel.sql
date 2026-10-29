/*---
id: qb.operator.prospect-funnel
description: "The caller operator's prospecting funnel by direction (Q48d): businesses found, researched, drafted, sent, followed up and the sponsors they brought, with how many of those were approved"
entities: [QbProspect, QbProspectDirection, QbSponsor, QbOperatorMember]
datasets:
  QbProspect: urn:jabiz:dataset:default:QbProspect
  QbProspectDirection: urn:jabiz:dataset:default:QbProspectDirection
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
results:
  directionId: { from: QbProspectDirection.directionId }
  name:        { from: QbProspectDirection.name }
  found:       { kind: { type: numeric, precision: 9, scale: 0 } }
  researched:  { kind: { type: numeric, precision: 9, scale: 0 } }
  drafted:     { kind: { type: numeric, precision: 9, scale: 0 } }
  sent:        { kind: { type: numeric, precision: 9, scale: 0 } }
  followedUp:  { kind: { type: numeric, precision: 9, scale: 0 } }
  signedUp:    { kind: { type: numeric, precision: 9, scale: 0 } }
  approved:    { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [name]
  defaultSort: { field: name, asc: true }
  key: [directionId]
permissions: [qb.prospect.use]
---*/
-- Counts only, by direction: drafted counts every business that got a draft (approved, sent or discarded after),
-- signedUp the sponsors a trial brought, approved those whose onboarding is approved now.
SELECT d.{{QbProspectDirection.directionId}} AS directionId,
       d.{{QbProspectDirection.name}} AS name,
       count(p.{{QbProspect.prospectId}}) AS found,
       count(p.{{QbProspect.researchedAt}}) AS researched,
       count(*) FILTER (WHERE p.{{QbProspect.mailSubject}} IS NOT NULL) AS drafted,
       count(p.{{QbProspect.sentAt}}) AS sent,
       count(p.{{QbProspect.followedUpAt}}) AS followedUp,
       count(p.{{QbProspect.convertedUserId}}) AS signedUp,
       count(*) FILTER (WHERE s.{{QbSponsor.reviewStatus}} = 'APPROVED') AS approved
FROM {{QbProspect}} p
JOIN {{QbProspectDirection}} d ON d.{{QbProspectDirection.directionId}} = p.{{QbProspect.directionId}}
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = p.{{QbProspect.convertedUserId}}
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
  AND me.{{QbOperatorMember.operatorId}} = p.{{QbProspect.owner}}
WHERE true
GROUP BY d.{{QbProspectDirection.directionId}}, d.{{QbProspectDirection.name}}
