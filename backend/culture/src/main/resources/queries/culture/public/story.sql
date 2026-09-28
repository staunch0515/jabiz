/*---
id: culture.public.story
description: A story page's header, player, texts and the three reflection questions
access: public
cacheSeconds: 60
entities: [Story]
datasets: { Story: "urn:jabiz:dataset:public:Story" }
params:
  slug: { like: Story.slug, required: true }
results:
  storyId:             { from: Story.storyId }
  slug:                { from: Story.slug }
  title:               { from: Story.title }
  summary:             { from: Story.summary }
  about:               { from: Story.about }
  body:                { from: Story.body }
  mediaType:           { from: Story.mediaType }
  storyDate:           { from: Story.storyDate }
  thumbnailFileId:     { from: Story.thumbnailFileId }
  thumbnailAlt:        { from: Story.thumbnailAlt }
  videoProvider:       { from: Story.videoProvider }
  videoId:             { from: Story.videoId }
  transcript:          { from: Story.transcript }
  reflectionSurprised: { from: Story.reflectionSurprised }
  reflectionAssumed:   { from: Story.reflectionAssumed }
  reflectionLearned:   { from: Story.reflectionLearned }
  publishedTime:       { from: Story.publishedTime }
list:
  key: [storyId]
---*/
SELECT
    s.{{Story.storyId}}             AS storyId,
    s.{{Story.slug}}                AS slug,
    s.{{Story.title}}               AS title,
    s.{{Story.summary}}             AS summary,
    s.{{Story.about}}               AS about,
    s.{{Story.body}}                AS body,
    s.{{Story.mediaType}}           AS mediaType,
    s.{{Story.storyDate}}           AS storyDate,
    s.{{Story.thumbnailFileId}}     AS thumbnailFileId,
    s.{{Story.thumbnailAlt}}        AS thumbnailAlt,
    s.{{Story.videoProvider}}       AS videoProvider,
    s.{{Story.videoId}}             AS videoId,
    s.{{Story.transcript}}          AS transcript,
    s.{{Story.reflectionSurprised}} AS reflectionSurprised,
    s.{{Story.reflectionAssumed}}   AS reflectionAssumed,
    s.{{Story.reflectionLearned}}   AS reflectionLearned,
    s.{{Story.publishedTime}}       AS publishedTime
FROM {{Story}} s
WHERE s.{{Story.slug}} = :slug
