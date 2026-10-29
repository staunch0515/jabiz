/*---
id: qb.admin.operators
description: "Every operator (Q48a) with its current members and its sponsors, newest first"
entities: [QbOperator, QbOperatorMember, QbSponsorOperator]
datasets:
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbOperatorMember: urn:jabiz:dataset:default:QbOperatorMember
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
params:
  operatorId: { like: QbOperator.operatorId, description: Only this operator (its page) }
results:
  operatorId:   { from: QbOperator.operatorId }
  name:         { from: QbOperator.name }
  contactName:  { from: QbOperator.contactName }
  contactEmail: { from: QbOperator.contactEmail }
  collection:   { from: QbOperator.collection }
  status:       { from: QbOperator.status }
  note:         { from: QbOperator.note }
  createdAt:    { from: QbOperator.createdAt }
  revision:     { from: QbOperator.revision }
  members:      { kind: { type: numeric, precision: 9, scale: 0 } }
  sponsors:     { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  filters: [status, createdAt]
  sorts:   [name, createdAt]
  defaultSort: { field: createdAt, asc: false }
  key: [operatorId]
permissions: [qb.admin.operators.read]
---*/
SELECT
    o.{{QbOperator.operatorId}}   AS operatorId,
    o.{{QbOperator.name}}         AS name,
    o.{{QbOperator.contactName}}  AS contactName,
    o.{{QbOperator.contactEmail}} AS contactEmail,
    o.{{QbOperator.collection}}   AS collection,
    o.{{QbOperator.status}}       AS status,
    o.{{QbOperator.note}}         AS note,
    o.{{QbOperator.createdAt}}    AS createdAt,
    o.{{QbOperator.revision}}     AS revision,
    (SELECT count(*) FROM {{QbOperatorMember}} m WHERE m.{{QbOperatorMember.operatorId}} = o.{{QbOperator.operatorId}}
        AND m.{{QbOperatorMember.removedAt}} IS NULL) AS members,
    (SELECT count(*) FROM {{QbSponsorOperator}} a
      WHERE a.{{QbSponsorOperator.operatorId}} = o.{{QbOperator.operatorId}}) AS sponsors
FROM {{QbOperator}} o
WHERE (CAST(:operatorId AS text) IS NULL OR o.{{QbOperator.operatorId}} = CAST(:operatorId AS text))
