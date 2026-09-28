/*---
id: culture.public.location_themes
description: The themes of the published stories in which someone from a place has a perspective (map)
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
  location: { like: Location.slug, required: true }
results:
  themeId:    { from: Theme.themeId }
  slug:       { from: Theme.slug }
  icon:       { from: Theme.icon }
  title:      { from: Theme.title }
  sortOrder:  { from: Theme.sortOrder }
  storyCount: { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts:   [sortOrder, storyCount]
  defaultSort: { field: sortOrder, asc: true }
  key: [themeId]
---*/
-- The same rule as the stories' place filter: a story is from a place when someone from there has a perspective in it.
SELECT
    t.{{Theme.themeId}}   AS themeId,
    t.{{Theme.slug}}      AS slug,
    t.{{Theme.icon}}      AS icon,
    t.{{Theme.title}}     AS title,
    t.{{Theme.sortOrder}} AS sortOrder,
    count(DISTINCT s.{{Story.storyId}}) AS storyCount
FROM {{Theme}} t
JOIN {{StoryTheme}} st ON st.{{StoryTheme.themeId}} = t.{{Theme.themeId}}
JOIN {{Story}} s ON s.{{Story.storyId}} = st.{{StoryTheme.storyId}}
JOIN {{Contribution}} c ON c.{{Contribution.storyId}} = s.{{Story.storyId}}
JOIN {{Participant}} p ON p.{{Participant.participantId}} = c.{{Contribution.participantId}}
JOIN {{Location}} l ON l.{{Location.locationId}} = p.{{Participant.locationId}}
WHERE l.{{Location.slug}} = :location
GROUP BY t.{{Theme.themeId}}, t.{{Theme.slug}}, t.{{Theme.icon}}, t.{{Theme.title}}, t.{{Theme.sortOrder}}
