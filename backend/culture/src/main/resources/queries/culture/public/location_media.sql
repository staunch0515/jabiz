/*---
id: culture.public.location_media
description: The photographs and videos of the people from a place, each with its published story (map)
access: public
cacheSeconds: 60
entities: [Story, Contribution, MediaItem, Participant, Location]
datasets:
  Story: "urn:jabiz:dataset:public:Story"
  Contribution: "urn:jabiz:dataset:public:Contribution"
  MediaItem: "urn:jabiz:dataset:public:MediaItem"
  Participant: "urn:jabiz:dataset:public:Participant"
  Location: "urn:jabiz:dataset:public:Location"
params:
  location: { like: Location.slug, required: true }
results:
  itemId:          { from: MediaItem.mediaItemId }
  kind:            { kind: { type: text, maxLength: 8 } }
  imageFileId:     { from: MediaItem.imageFileId }
  alt:             { from: MediaItem.alt }
  videoProvider:   { from: Contribution.videoProvider }
  videoId:         { from: Contribution.videoId }
  storySlug:       { from: Story.slug }
  storyTitle:      { from: Story.title }
  storyDate:       { from: Story.storyDate }
  participantSlug: { from: Participant.slug }
  displayName:     { from: Participant.displayName }
list:
  filters: [kind]
  sorts:   [storyDate, itemId]
  defaultSort: { field: storyDate, asc: false }
  key: [itemId]
---*/
-- kind: PHOTO (a photograph in someone's perspective: imageFileId and alt are the photograph's), VIDEO (the video of
-- someone's perspective, or a story's own video when someone from the place has a perspective in the story: imageFileId
-- and alt are the story's thumbnail, the cover shown before playing). participantSlug and displayName are empty for a
-- story's own video. itemId is the media item, perspective or story. Only what the story page shows: published
-- stories, public perspectives and media of active participants.
SELECT m.{{MediaItem.mediaItemId}} AS itemId, 'PHOTO' AS kind,
       m.{{MediaItem.imageFileId}} AS imageFileId, m.{{MediaItem.alt}} AS alt,
       CAST(NULL AS varchar) AS videoProvider, CAST(NULL AS varchar) AS videoId,
       s.{{Story.slug}} AS storySlug, s.{{Story.title}} AS storyTitle, s.{{Story.storyDate}} AS storyDate,
       p.{{Participant.slug}} AS participantSlug, p.{{Participant.displayName}} AS displayName
  FROM {{MediaItem}} m
  JOIN {{Story}} s ON s.{{Story.storyId}} = m.{{MediaItem.storyId}}
  JOIN {{Contribution}} c ON c.{{Contribution.contributionId}} = m.{{MediaItem.contributionId}}
  JOIN {{Participant}} p ON p.{{Participant.participantId}} = c.{{Contribution.participantId}}
  JOIN {{Location}} l ON l.{{Location.locationId}} = p.{{Participant.locationId}}
 WHERE l.{{Location.slug}} = :location
   AND m.{{MediaItem.kind}} = 'PHOTO'
   AND m.{{MediaItem.imageFileId}} IS NOT NULL
UNION ALL
SELECT c.{{Contribution.contributionId}}, 'VIDEO',
       s.{{Story.thumbnailFileId}}, s.{{Story.thumbnailAlt}},
       c.{{Contribution.videoProvider}}, c.{{Contribution.videoId}},
       s.{{Story.slug}}, s.{{Story.title}}, s.{{Story.storyDate}},
       p.{{Participant.slug}}, p.{{Participant.displayName}}
  FROM {{Contribution}} c
  JOIN {{Story}} s ON s.{{Story.storyId}} = c.{{Contribution.storyId}}
  JOIN {{Participant}} p ON p.{{Participant.participantId}} = c.{{Contribution.participantId}}
  JOIN {{Location}} l ON l.{{Location.locationId}} = p.{{Participant.locationId}}
 WHERE l.{{Location.slug}} = :location
   AND c.{{Contribution.videoId}} IS NOT NULL
UNION ALL
SELECT s.{{Story.storyId}}, 'VIDEO',
       s.{{Story.thumbnailFileId}}, s.{{Story.thumbnailAlt}},
       s.{{Story.videoProvider}}, s.{{Story.videoId}},
       s.{{Story.slug}}, s.{{Story.title}}, s.{{Story.storyDate}},
       NULL, NULL
  FROM {{Story}} s
 WHERE s.{{Story.videoId}} IS NOT NULL
   AND EXISTS (
        SELECT 1
          FROM {{Contribution}} c
          JOIN {{Participant}} p ON p.{{Participant.participantId}} = c.{{Contribution.participantId}}
          JOIN {{Location}} l ON l.{{Location.locationId}} = p.{{Participant.locationId}}
         WHERE c.{{Contribution.storyId}} = s.{{Story.storyId}}
           AND l.{{Location.slug}} = :location)
