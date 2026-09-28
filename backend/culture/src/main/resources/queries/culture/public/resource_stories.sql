/*---
id: culture.public.resource_stories
description: The published stories a teaching resource works with
access: public
cacheSeconds: 60
entities: [Resource, ResourceStory, Story]
datasets:
  Resource: "urn:jabiz:dataset:public:Resource"
  ResourceStory: "urn:jabiz:dataset:public:ResourceStory"
  Story: "urn:jabiz:dataset:public:Story"
params:
  slug: { like: Resource.slug, required: true }
results:
  storyId:         { from: Story.storyId }
  slug:            { from: Story.slug }
  title:           { from: Story.title }
  summary:         { from: Story.summary }
  mediaType:       { from: Story.mediaType }
  thumbnailFileId: { from: Story.thumbnailFileId }
  thumbnailAlt:    { from: Story.thumbnailAlt }
  storyDate:       { from: Story.storyDate }
list:
  sorts:   [storyDate, slug]
  defaultSort: { field: storyDate, asc: false }
  key: [storyId]
---*/
-- The links are public as such (two keys); joining the public stories keeps unpublished stories out.
SELECT
    s.{{Story.storyId}}         AS storyId,
    s.{{Story.slug}}            AS slug,
    s.{{Story.title}}           AS title,
    s.{{Story.summary}}         AS summary,
    s.{{Story.mediaType}}       AS mediaType,
    s.{{Story.thumbnailFileId}} AS thumbnailFileId,
    s.{{Story.thumbnailAlt}}    AS thumbnailAlt,
    s.{{Story.storyDate}}       AS storyDate
FROM {{Resource}} r
JOIN {{ResourceStory}} rs ON rs.{{ResourceStory.resourceId}} = r.{{Resource.resourceId}}
JOIN {{Story}} s ON s.{{Story.storyId}} = rs.{{ResourceStory.storyId}}
WHERE r.{{Resource.slug}} = :slug
