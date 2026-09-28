/*---
id: culture.public.resource
description: A teaching resource's page, with its steps to read on the page and its PDF
access: public
cacheSeconds: 60
entities: [Resource]
datasets: { Resource: "urn:jabiz:dataset:public:Resource" }
params:
  slug: { like: Resource.slug, required: true }
results:
  resourceId:      { from: Resource.resourceId }
  slug:            { from: Resource.slug }
  title:           { from: Resource.title }
  description:     { from: Resource.description }
  activityType:    { from: Resource.activityType }
  ageGroup:        { from: Resource.ageGroup }
  durationMinutes: { from: Resource.durationMinutes }
  pdfFileId:       { from: Resource.pdfFileId }
  body:            { from: Resource.body }
list:
  key: [resourceId]
---*/
SELECT
    r.{{Resource.resourceId}}      AS resourceId,
    r.{{Resource.slug}}            AS slug,
    r.{{Resource.title}}           AS title,
    r.{{Resource.description}}     AS description,
    r.{{Resource.activityType}}    AS activityType,
    r.{{Resource.ageGroup}}        AS ageGroup,
    r.{{Resource.durationMinutes}} AS durationMinutes,
    r.{{Resource.pdfFileId}}       AS pdfFileId,
    r.{{Resource.body}}            AS body
FROM {{Resource}} r
WHERE r.{{Resource.slug}} = :slug
