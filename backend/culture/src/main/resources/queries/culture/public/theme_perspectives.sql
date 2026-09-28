/*---
id: culture.public.theme_perspectives
description: Every perspective in the published stories of a theme, place by place, to compare answers to its question
access: public
cacheSeconds: 60
entities: [Theme, StoryTheme, Story, Contribution, Participant, Location]
datasets:
  Theme: "urn:jabiz:dataset:public:Theme"
  StoryTheme: "urn:jabiz:dataset:public:StoryTheme"
  Story: "urn:jabiz:dataset:public:Story"
  Contribution: "urn:jabiz:dataset:public:Contribution"
  Participant: "urn:jabiz:dataset:public:Participant"
  Location: "urn:jabiz:dataset:public:Location"
params:
  slug: { like: Theme.slug, required: true }
results:
  contributionId:    { from: Contribution.contributionId }
  heading:           { from: Contribution.heading }
  text:              { from: Contribution.text }
  videoProvider:     { from: Contribution.videoProvider }
  videoId:           { from: Contribution.videoId }
  transcript:        { from: Contribution.transcript }
  audioFileId:       { from: Contribution.audioFileId }
  sortOrder:         { from: Contribution.sortOrder }
  storyId:           { from: Story.storyId }
  storySlug:         { from: Story.slug }
  storyTitle:        { from: Story.title }
  mediaType:         { from: Story.mediaType }
  thumbnailFileId:   { from: Story.thumbnailFileId }
  thumbnailAlt:      { from: Story.thumbnailAlt }
  participantId:     { from: Participant.participantId }
  participantSlug:   { from: Participant.slug }
  displayName:       { from: Participant.displayName }
  portraitFileId:    { from: Participant.portraitFileId }
  portraitAlt:       { from: Participant.portraitAlt }
  locationSlug:      { from: Location.slug }
  locationName:      { from: Location.name }
  countryCode:       { from: Location.countryCode }
  locationSortOrder: { from: Location.sortOrder }
list:
  filters: [locationSlug, mediaType]
  sorts:   [locationSortOrder, locationSlug, sortOrder]
  defaultSort: { field: locationSortOrder, asc: true }
  key: [contributionId]
---*/
-- Flat rows ordered by place; the site groups them (section 9.1: side by side or one by one).
SELECT
    c.{{Contribution.contributionId}} AS contributionId,
    c.{{Contribution.heading}}        AS heading,
    c.{{Contribution.text}}           AS text,
    c.{{Contribution.videoProvider}}  AS videoProvider,
    c.{{Contribution.videoId}}        AS videoId,
    c.{{Contribution.transcript}}     AS transcript,
    c.{{Contribution.audioFileId}}    AS audioFileId,
    c.{{Contribution.sortOrder}}      AS sortOrder,
    s.{{Story.storyId}}               AS storyId,
    s.{{Story.slug}}                  AS storySlug,
    s.{{Story.title}}                 AS storyTitle,
    s.{{Story.mediaType}}             AS mediaType,
    s.{{Story.thumbnailFileId}}       AS thumbnailFileId,
    s.{{Story.thumbnailAlt}}          AS thumbnailAlt,
    p.{{Participant.participantId}}   AS participantId,
    p.{{Participant.slug}}            AS participantSlug,
    p.{{Participant.displayName}}     AS displayName,
    p.{{Participant.portraitFileId}}  AS portraitFileId,
    p.{{Participant.portraitAlt}}     AS portraitAlt,
    l.{{Location.slug}}               AS locationSlug,
    l.{{Location.name}}               AS locationName,
    l.{{Location.countryCode}}        AS countryCode,
    l.{{Location.sortOrder}}          AS locationSortOrder
FROM {{Theme}} t
JOIN {{StoryTheme}} st ON st.{{StoryTheme.themeId}} = t.{{Theme.themeId}}
JOIN {{Story}} s ON s.{{Story.storyId}} = st.{{StoryTheme.storyId}}
JOIN {{Contribution}} c ON c.{{Contribution.storyId}} = s.{{Story.storyId}}
JOIN {{Participant}} p ON p.{{Participant.participantId}} = c.{{Contribution.participantId}}
LEFT JOIN {{Location}} l ON l.{{Location.locationId}} = p.{{Participant.locationId}}
WHERE t.{{Theme.slug}} = :slug
