/*---
id: qb.sponsor.operator
description: >-
  The operator that looks after the Sponsor now (F-S1b, "your operator"): its name, language and how to reach it, and
  whether the Sponsor is the operator's own; no row before the Sponsor has an operator
entities: [QbSponsorOperator, QbOperator]
datasets:
  QbSponsorOperator: urn:jabiz:dataset:sponsor:QbSponsorOperator
  QbOperator: urn:jabiz:dataset:default:QbOperator
results:
  operatorId:   { kind: { type: text, maxLength: 36 } }
  name:         { kind: { type: text, maxLength: 200 } }
  language:     { kind: { type: text, maxLength: 10 } }
  contactName:  { kind: { type: text, maxLength: 100 } }
  contactEmail: { kind: { type: text, maxLength: 254 } }
  own:          { kind: { type: bool } }
  assignedAt:   { kind: { type: temporal, role: EVENT_TIME } }
permissions: [qb.sponsor.me]
---*/
-- At most one row: the caller's own assignment (the dataset is limited to the caller) and its operator. The operator's
-- note, members and money are not here: the Sponsor sees whom to ask, nothing of the operator's own business.
SELECT o.{{QbOperator.operatorId}}   AS operatorId,
       o.{{QbOperator.name}}         AS name,
       o.{{QbOperator.language}}     AS language,
       o.{{QbOperator.contactName}}  AS contactName,
       o.{{QbOperator.contactEmail}} AS contactEmail,
       a.{{QbSponsorOperator.fixed}} AS own,
       a.{{QbSponsorOperator.assignedAt}} AS assignedAt
FROM {{QbSponsorOperator}} a
JOIN {{QbOperator}} o ON o.{{QbOperator.operatorId}} = a.{{QbSponsorOperator.operatorId}}
