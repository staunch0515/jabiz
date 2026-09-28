/*---
id: culture.public.person_themes
description: The themes of the published stories a participant has a perspective in
access: public
cacheSeconds: 60
entities: [Theme, StoryTheme, Story, Contribution, Participant]
datasets:
  Theme: "urn:jabiz:dataset:public:Theme"
  StoryTheme: "urn:jabiz:dataset:public:StoryTheme"
  Story: "urn:jabiz:dataset:public:Story"
  Contribution: "urn:jabiz:dataset:public:Contribution"
  Participant: "urn:jabiz:dataset:public:Participant"
params:
  slug: { like: Participant.slug, required: true }
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
WHERE p.{{Participant.slug}} = :slug
GROUP BY t.{{Theme.themeId}}, t.{{Theme.slug}}, t.{{Theme.icon}}, t.{{Theme.title}}, t.{{Theme.sortOrder}}
