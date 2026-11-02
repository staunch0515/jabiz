/*---
id: qb.sponsor.publication-prerequisites
description: The publications a sponsor's publication requires first (Q76), with their names and states, for the wizard and the publication's page
entities: [QbPublicationPrerequisite, QbPublication]
datasets:
  QbPublicationPrerequisite: urn:jabiz:dataset:sponsor:QbPublicationPrerequisite
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
params:
  publicationId: { like: QbPublicationPrerequisite.publicationId, required: true, description: The publication whose prerequisites are listed }
results:
  prerequisiteId:        { from: QbPublicationPrerequisite.prerequisiteId }
  requiredPublicationId: { from: QbPublicationPrerequisite.requiredPublicationId }
  requirement:           { from: QbPublicationPrerequisite.requirement }
  name:                  { from: QbPublication.name }
  status:                { from: QbPublication.status }
  startAt:               { from: QbPublication.startAt }
  endAt:                 { from: QbPublication.endAt }
list:
  key: [prerequisiteId]
  sorts: [name]
  defaultSort: { field: name, asc: true }
permissions: [qb.publication.write]
---*/
-- The sponsor's own rows only (both views hold the caller's). A required publication is always the sponsor's own
-- (PrerequisiteRules), so the join finds it; its current name and state tell the sponsor what the chain looks like now.
SELECT
    pr.{{QbPublicationPrerequisite.prerequisiteId}}        AS prerequisiteId,
    pr.{{QbPublicationPrerequisite.requiredPublicationId}} AS requiredPublicationId,
    pr.{{QbPublicationPrerequisite.requirement}}           AS requirement,
    p.{{QbPublication.name}}                               AS name,
    p.{{QbPublication.status}}                             AS status,
    p.{{QbPublication.startAt}}                            AS startAt,
    p.{{QbPublication.endAt}}                              AS endAt
FROM {{QbPublicationPrerequisite}} pr
JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = pr.{{QbPublicationPrerequisite.requiredPublicationId}}
WHERE pr.{{QbPublicationPrerequisite.publicationId}} = :publicationId
