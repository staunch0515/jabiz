/*---
id: qb.operator.prospects
description: "The caller operator's businesses found by AI prospecting (Q48c), with the research, the draft and its state; one by prospectId for the review page"
entities: [QbProspect, QbOperatorMember]
datasets:
  QbProspect: urn:jabiz:dataset:default:QbProspect
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
params:
  prospectId: { like: QbProspect.prospectId, description: Only this business (the review page) }
  status:     { like: QbProspect.status, description: Only businesses in this state }
  q:          { kind: { type: text, maxLength: 100 }, description: Only businesses whose domain or name contains this text }
results:
  prospectId:       { from: QbProspect.prospectId }
  owner:            { from: QbProspect.owner }
  directionId:      { from: QbProspect.directionId }
  domain:           { from: QbProspect.domain }
  websiteUrl:       { from: QbProspect.websiteUrl }
  company:          { from: QbProspect.company }
  status:           { from: QbProspect.status }
  foundAt:          { from: QbProspect.foundAt }
  researchedAt:     { from: QbProspect.researchedAt }
  language:         { from: QbProspect.language }
  country:          { from: QbProspect.country }
  sellsOnline:      { from: QbProspect.sellsOnline }
  shopify:          { from: QbProspect.shopify }
  publicEmail:      { from: QbProspect.publicEmail }
  refusesMarketing: { from: QbProspect.refusesMarketing }
  score:            { from: QbProspect.score }
  scoreReasons:     { from: QbProspect.scoreReasons }
  products:         { from: QbProspect.products }
  trial:            { from: QbProspect.trial }
  mailSubject:      { from: QbProspect.mailSubject }
  mailBody:         { from: QbProspect.mailBody }
  failure:          { from: QbProspect.failure }
  approvedAt:       { from: QbProspect.approvedAt }
  approvedBy:       { from: QbProspect.approvedBy }
  sentAt:           { from: QbProspect.sentAt }
  discardedAt:      { from: QbProspect.discardedAt }
  revision:         { from: QbProspect.revision }
list:
  filters: [status, foundAt, sentAt]
  sorts:   [foundAt, score, sentAt]
  defaultSort: { field: foundAt, asc: false }
  key: [prospectId]
permissions: [qb.prospect.use]
---*/
-- The caller's operator's only: the membership dataset is the caller's own, current membership. The trial key is not
-- returned (it is the mail's link; the page shows the trial from the draft).
SELECT
    p.{{QbProspect.prospectId}} AS prospectId, p.{{QbProspect.owner}} AS owner,
    p.{{QbProspect.directionId}} AS directionId, p.{{QbProspect.domain}} AS domain,
    p.{{QbProspect.websiteUrl}} AS websiteUrl, p.{{QbProspect.company}} AS company,
    p.{{QbProspect.status}} AS status, p.{{QbProspect.foundAt}} AS foundAt,
    p.{{QbProspect.researchedAt}} AS researchedAt, p.{{QbProspect.language}} AS language,
    p.{{QbProspect.country}} AS country, p.{{QbProspect.sellsOnline}} AS sellsOnline,
    p.{{QbProspect.shopify}} AS shopify, p.{{QbProspect.publicEmail}} AS publicEmail,
    p.{{QbProspect.refusesMarketing}} AS refusesMarketing, p.{{QbProspect.score}} AS score,
    p.{{QbProspect.scoreReasons}} AS scoreReasons, p.{{QbProspect.products}} AS products,
    p.{{QbProspect.trial}} AS trial, p.{{QbProspect.mailSubject}} AS mailSubject,
    p.{{QbProspect.mailBody}} AS mailBody, p.{{QbProspect.failure}} AS failure,
    p.{{QbProspect.approvedAt}} AS approvedAt, p.{{QbProspect.approvedBy}} AS approvedBy,
    p.{{QbProspect.sentAt}} AS sentAt, p.{{QbProspect.discardedAt}} AS discardedAt,
    p.{{QbProspect.revision}} AS revision
FROM {{QbProspect}} p
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
  AND CAST(me.{{QbOperatorMember.operatorId}} AS text) = p.{{QbProspect.owner}}
WHERE true
  AND (CAST(:prospectId AS text) IS NULL OR p.{{QbProspect.prospectId}} = :prospectId)
  AND (CAST(:status AS text) IS NULL OR p.{{QbProspect.status}} = :status)
  AND (CAST(:q AS text) IS NULL OR strpos(lower(p.{{QbProspect.domain}} || ' ' || coalesce(p.{{QbProspect.company}}, '')), lower(:q)) > 0)
