/*---
id: culture.public.story_media
description: The photos and audio of a published story, its own and those of its shown perspectives
access: public
cacheSeconds: 60
entities: [Story, MediaItem, Contribution, Participant]
datasets:
  Story: "urn:jabiz:dataset:public:Story"
  MediaItem: "urn:jabiz:dataset:public:MediaItem"
  Contribution: "urn:jabiz:dataset:public:Contribution"
  Participant: "urn:jabiz:dataset:public:Participant"
params:
  slug: { like: Story.slug, required: true }
results:
  mediaItemId:    { from: MediaItem.mediaItemId }
  contributionId: { from: MediaItem.contributionId }
  kind:           { from: MediaItem.kind }
  imageFileId:    { from: MediaItem.imageFileId }
  audioFileId:    { from: MediaItem.audioFileId }
  alt:            { from: MediaItem.alt }
  caption:        { from: MediaItem.caption }
  credit:         { from: MediaItem.credit }
  sortOrder:      { from: MediaItem.sortOrder }
list:
  filters: [kind, contributionId]
  sorts:   [sortOrder]
  defaultSort: { field: sortOrder, asc: true }
  key: [mediaItemId]
---*/
-- A perspective's media show with the perspective only (the same rule as story_perspectives).
SELECT
    m.{{MediaItem.mediaItemId}}    AS mediaItemId,
    m.{{MediaItem.contributionId}} AS contributionId,
    m.{{MediaItem.kind}}           AS kind,
    m.{{MediaItem.imageFileId}}    AS imageFileId,
    m.{{MediaItem.audioFileId}}    AS audioFileId,
    m.{{MediaItem.alt}}            AS alt,
    m.{{MediaItem.caption}}        AS caption,
    m.{{MediaItem.credit}}         AS credit,
    m.{{MediaItem.sortOrder}}      AS sortOrder
FROM {{Story}} s
JOIN {{MediaItem}} m ON m.{{MediaItem.storyId}} = s.{{Story.storyId}}
WHERE s.{{Story.slug}} = :slug
  AND (m.{{MediaItem.contributionId}} IS NULL OR EXISTS (
        SELECT 1
          FROM {{Contribution}} c
          JOIN {{Participant}} p ON p.{{Participant.participantId}} = c.{{Contribution.participantId}}
         WHERE c.{{Contribution.contributionId}} = m.{{MediaItem.contributionId}}))
