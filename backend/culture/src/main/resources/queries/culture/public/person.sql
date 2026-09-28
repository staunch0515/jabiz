/*---
id: culture.public.person
description: A participant's page, header and texts
access: public
cacheSeconds: 60
entities: [Participant, Location]
datasets:
  Participant: "urn:jabiz:dataset:public:Participant"
  Location: "urn:jabiz:dataset:public:Location"
params:
  slug: { like: Participant.slug, required: true }
results:
  participantId:  { from: Participant.participantId }
  slug:           { from: Participant.slug }
  displayName:    { from: Participant.displayName }
  portraitFileId: { from: Participant.portraitFileId }
  portraitAlt:    { from: Participant.portraitAlt }
  shortBio:       { from: Participant.shortBio }
  bio:            { from: Participant.bio }
  perspective:    { from: Participant.perspective }
  reflection:     { from: Participant.reflection }
  interests:      { from: Participant.interests }
  languages:      { from: Participant.languages }
  locationSlug:   { from: Location.slug }
  locationName:   { from: Location.name }
  countryCode:    { from: Location.countryCode }
  placeLabel:     { from: Location.placeLabel }
list:
  key: [participantId]
---*/
SELECT
    p.{{Participant.participantId}}  AS participantId,
    p.{{Participant.slug}}           AS slug,
    p.{{Participant.displayName}}    AS displayName,
    p.{{Participant.portraitFileId}} AS portraitFileId,
    p.{{Participant.portraitAlt}}    AS portraitAlt,
    p.{{Participant.shortBio}}       AS shortBio,
    p.{{Participant.bio}}            AS bio,
    p.{{Participant.perspective}}    AS perspective,
    p.{{Participant.reflection}}     AS reflection,
    p.{{Participant.interests}}      AS interests,
    p.{{Participant.languages}}      AS languages,
    l.{{Location.slug}}              AS locationSlug,
    l.{{Location.name}}              AS locationName,
    l.{{Location.countryCode}}       AS countryCode,
    l.{{Location.placeLabel}}        AS placeLabel
FROM {{Participant}} p
LEFT JOIN {{Location}} l ON l.{{Location.locationId}} = p.{{Participant.locationId}}
WHERE p.{{Participant.slug}} = :slug
