/*---
id: culture.public.person_stories
description: The published stories a participant has a perspective in
access: public
cacheSeconds: 60
entities: [Story, Contribution, Participant]
datasets:
  Story: "urn:jabiz:dataset:public:Story"
  Contribution: "urn:jabiz:dataset:public:Contribution"
  Participant: "urn:jabiz:dataset:public:Participant"
params:
  slug: { like: Participant.slug, required: true }
results:
  storyId:         { from: Story.storyId }
  slug:            { from: Story.slug }
  title:           { from: Story.title }
  summary:         { from: Story.summary }
  mediaType:       { from: Story.mediaType }
  storyDate:       { from: Story.storyDate }
  thumbnailFileId: { from: Story.thumbnailFileId }
  thumbnailAlt:    { from: Story.thumbnailAlt }
  publishedTime:   { from: Story.publishedTime }
list:
  filters: [mediaType]
  sorts:   [storyDate, publishedTime]
  defaultSort: { field: storyDate, asc: false }
  key: [storyId]
---*/
SELECT
    s.{{Story.storyId}}         AS storyId,
    s.{{Story.slug}}            AS slug,
    s.{{Story.title}}           AS title,
    s.{{Story.summary}}         AS summary,
    s.{{Story.mediaType}}       AS mediaType,
    s.{{Story.storyDate}}       AS storyDate,
    s.{{Story.thumbnailFileId}} AS thumbnailFileId,
    s.{{Story.thumbnailAlt}}    AS thumbnailAlt,
    s.{{Story.publishedTime}}   AS publishedTime
FROM {{Story}} s
WHERE EXISTS (
    SELECT 1
      FROM {{Contribution}} c
      JOIN {{Participant}} p ON p.{{Participant.participantId}} = c.{{Contribution.participantId}}
     WHERE c.{{Contribution.storyId}} = s.{{Story.storyId}}
       AND p.{{Participant.slug}} = :slug)
