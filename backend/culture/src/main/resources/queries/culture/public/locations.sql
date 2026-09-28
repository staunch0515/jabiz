/*---
id: culture.public.locations
description: Places with how many participants and published stories each has (map, filters)
access: public
cacheSeconds: 60
entities: [Location, Participant, Contribution, Story]
datasets:
  Location: "urn:jabiz:dataset:public:Location"
  Participant: "urn:jabiz:dataset:public:Participant"
  Contribution: "urn:jabiz:dataset:public:Contribution"
  Story: "urn:jabiz:dataset:public:Story"
results:
  locationId:       { from: Location.locationId }
  slug:             { from: Location.slug }
  name:             { from: Location.name }
  countryCode:      { from: Location.countryCode }
  placeLabel:       { from: Location.placeLabel }
  latitude:         { from: Location.latitude }
  longitude:        { from: Location.longitude }
  sortOrder:        { from: Location.sortOrder }
  participantCount: { kind: { type: numeric, precision: 9, scale: 0 } }
  storyCount:       { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  filters: [slug, countryCode]
  sorts:   [sortOrder, slug]
  defaultSort: { field: sortOrder, asc: true }
  key: [locationId]
---*/
-- A story counts for a place when a participant from there has a perspective in it: stories belong to people.
SELECT
    l.{{Location.locationId}}  AS locationId,
    l.{{Location.slug}}        AS slug,
    l.{{Location.name}}        AS name,
    l.{{Location.countryCode}} AS countryCode,
    l.{{Location.placeLabel}}  AS placeLabel,
    l.{{Location.latitude}}    AS latitude,
    l.{{Location.longitude}}   AS longitude,
    l.{{Location.sortOrder}}   AS sortOrder,
    (SELECT count(*)
       FROM {{Participant}} p
      WHERE p.{{Participant.locationId}} = l.{{Location.locationId}}) AS participantCount,
    (SELECT count(DISTINCT s.{{Story.storyId}})
       FROM {{Story}} s
       JOIN {{Contribution}} c ON c.{{Contribution.storyId}} = s.{{Story.storyId}}
       JOIN {{Participant}} cp ON cp.{{Participant.participantId}} = c.{{Contribution.participantId}}
      WHERE cp.{{Participant.locationId}} = l.{{Location.locationId}}) AS storyCount
FROM {{Location}} l
