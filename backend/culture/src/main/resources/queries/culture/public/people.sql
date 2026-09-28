/*---
id: culture.public.people
description: Participants' cards, optionally of some places
access: public
cacheSeconds: 60
entities: [Participant, Location]
datasets:
  Participant: "urn:jabiz:dataset:public:Participant"
  Location: "urn:jabiz:dataset:public:Location"
params:
  location: { like: Location.slug, list: true, description: Only participants from these places }
results:
  participantId:  { from: Participant.participantId }
  slug:           { from: Participant.slug }
  displayName:    { from: Participant.displayName }
  portraitFileId: { from: Participant.portraitFileId }
  portraitAlt:    { from: Participant.portraitAlt }
  shortBio:       { from: Participant.shortBio }
  interests:      { from: Participant.interests }
  sortOrder:      { from: Participant.sortOrder }
  locationSlug:   { from: Location.slug }
  locationName:   { from: Location.name }
  countryCode:    { from: Location.countryCode }
list:
  filters: [locationSlug]
  sorts:   [sortOrder, displayName]
  defaultSort: { field: sortOrder, asc: true }
  key: [participantId]
---*/
-- A hidden place hides its label, not its people.
SELECT
    p.{{Participant.participantId}}  AS participantId,
    p.{{Participant.slug}}           AS slug,
    p.{{Participant.displayName}}    AS displayName,
    p.{{Participant.portraitFileId}} AS portraitFileId,
    p.{{Participant.portraitAlt}}    AS portraitAlt,
    p.{{Participant.shortBio}}       AS shortBio,
    p.{{Participant.interests}}      AS interests,
    p.{{Participant.sortOrder}}      AS sortOrder,
    l.{{Location.slug}}              AS locationSlug,
    l.{{Location.name}}              AS locationName,
    l.{{Location.countryCode}}       AS countryCode
FROM {{Participant}} p
LEFT JOIN {{Location}} l ON l.{{Location.locationId}} = p.{{Participant.locationId}}
WHERE CAST(:location AS varchar[]) IS NULL OR l.{{Location.slug}} = ANY(:location)
