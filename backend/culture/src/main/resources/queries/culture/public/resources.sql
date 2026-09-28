/*---
id: culture.public.resources
description: Teaching resources, optionally of some activity types and age groups
access: public
cacheSeconds: 60
entities: [Resource]
datasets: { Resource: "urn:jabiz:dataset:public:Resource" }
params:
  activityType: { like: Resource.activityType, list: true }
  ageGroup:     { like: Resource.ageGroup, list: true }
results:
  resourceId:      { from: Resource.resourceId }
  slug:            { from: Resource.slug }
  title:           { from: Resource.title }
  description:     { from: Resource.description }
  activityType:    { from: Resource.activityType }
  ageGroup:        { from: Resource.ageGroup }
  durationMinutes: { from: Resource.durationMinutes }
  pdfFileId:       { from: Resource.pdfFileId }
  sortOrder:       { from: Resource.sortOrder }
list:
  filters: [activityType, ageGroup, durationMinutes]
  sorts:   [sortOrder, durationMinutes, slug]
  defaultSort: { field: sortOrder, asc: true }
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
    r.{{Resource.sortOrder}}       AS sortOrder
FROM {{Resource}} r
WHERE (CAST(:activityType AS varchar[]) IS NULL OR r.{{Resource.activityType}} = ANY(:activityType))
  AND (CAST(:ageGroup AS varchar[]) IS NULL OR r.{{Resource.ageGroup}} = ANY(:ageGroup))
