/*---
id: qb.admin.prospect-directions
description: "An owner's directions of search (Q48c), with what each found"
entities: [QbProspectDirection, QbProspect]
datasets:
  QbProspectDirection: urn:jabiz:dataset:default:QbProspectDirection
  QbProspect: urn:jabiz:dataset:default:QbProspect
params:
  owner: { like: QbProspectDirection.owner, required: true, description: "The owner: an operator's id, or direct (headquarters)" }
results:
  directionId:  { from: QbProspectDirection.directionId }
  name:         { from: QbProspectDirection.name }
  brief:        { from: QbProspectDirection.brief }
  country:      { from: QbProspectDirection.country }
  language:     { from: QbProspectDirection.language }
  active:       { from: QbProspectDirection.active }
  perSearch:    { from: QbProspectDirection.perSearch }
  lastSearchAt: { from: QbProspectDirection.lastSearchAt }
  createdAt:    { from: QbProspectDirection.createdAt }
  revision:     { from: QbProspectDirection.revision }
  found:        { kind: { type: numeric, precision: 9, scale: 0 } }
  sent:         { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [createdAt, name]
  defaultSort: { field: createdAt, asc: false }
  key: [directionId]
permissions: [qb.admin.prospect]
---*/
SELECT
    d.{{QbProspectDirection.directionId}} AS directionId, d.{{QbProspectDirection.name}} AS name,
    d.{{QbProspectDirection.brief}} AS brief, d.{{QbProspectDirection.country}} AS country,
    d.{{QbProspectDirection.language}} AS language, d.{{QbProspectDirection.active}} AS active,
    d.{{QbProspectDirection.perSearch}} AS perSearch, d.{{QbProspectDirection.lastSearchAt}} AS lastSearchAt,
    d.{{QbProspectDirection.createdAt}} AS createdAt, d.{{QbProspectDirection.revision}} AS revision,
    (SELECT count(*) FROM {{QbProspect}} f WHERE f.{{QbProspect.directionId}} = d.{{QbProspectDirection.directionId}}) AS found,
    (SELECT count(*) FROM {{QbProspect}} f WHERE f.{{QbProspect.directionId}} = d.{{QbProspectDirection.directionId}}
       AND f.{{QbProspect.status}} = 'SENT') AS sent
FROM {{QbProspectDirection}} d
WHERE d.{{QbProspectDirection.owner}} = :owner
