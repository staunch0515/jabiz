/*---
id: culture.public.story_perspectives
description: The perspectives of a published story, each with its participant and place
access: public
cacheSeconds: 60
entities: [Story, Contribution, Participant, Location]
datasets:
  Story: "urn:jabiz:dataset:public:Story"
  Contribution: "urn:jabiz:dataset:public:Contribution"
  Participant: "urn:jabiz:dataset:public:Participant"
  Location: "urn:jabiz:dataset:public:Location"
params:
  slug: { like: Story.slug, required: true }
results:
  contributionId:  { from: Contribution.contributionId }
  heading:         { from: Contribution.heading }
  text:            { from: Contribution.text }
  videoProvider:   { from: Contribution.videoProvider }
  videoId:         { from: Contribution.videoId }
  transcript:      { from: Contribution.transcript }
  audioFileId:     { from: Contribution.audioFileId }
  sortOrder:       { from: Contribution.sortOrder }
  participantId:   { from: Participant.participantId }
  participantSlug: { from: Participant.slug }
  displayName:     { from: Participant.displayName }
  portraitFileId:  { from: Participant.portraitFileId }
  portraitAlt:     { from: Participant.portraitAlt }
  locationSlug:    { from: Location.slug }
  locationName:    { from: Location.name }
  countryCode:     { from: Location.countryCode }
list:
  sorts:   [sortOrder]
  defaultSort: { field: sortOrder, asc: true }
  key: [contributionId]
---*/
-- Only active participants: a hidden participant's perspectives are not shown while the story stays published.
SELECT
    c.{{Contribution.contributionId}} AS contributionId,
    c.{{Contribution.heading}}        AS heading,
    c.{{Contribution.text}}           AS text,
    c.{{Contribution.videoProvider}}  AS videoProvider,
    c.{{Contribution.videoId}}        AS videoId,
    c.{{Contribution.transcript}}     AS transcript,
    c.{{Contribution.audioFileId}}    AS audioFileId,
    c.{{Contribution.sortOrder}}      AS sortOrder,
    p.{{Participant.participantId}}   AS participantId,
    p.{{Participant.slug}}            AS participantSlug,
    p.{{Participant.displayName}}     AS displayName,
    p.{{Participant.portraitFileId}}  AS portraitFileId,
    p.{{Participant.portraitAlt}}     AS portraitAlt,
    l.{{Location.slug}}               AS locationSlug,
    l.{{Location.name}}               AS locationName,
    l.{{Location.countryCode}}        AS countryCode
FROM {{Story}} s
JOIN {{Contribution}} c ON c.{{Contribution.storyId}} = s.{{Story.storyId}}
JOIN {{Participant}} p ON p.{{Participant.participantId}} = c.{{Contribution.participantId}}
LEFT JOIN {{Location}} l ON l.{{Location.locationId}} = p.{{Participant.locationId}}
WHERE s.{{Story.slug}} = :slug
