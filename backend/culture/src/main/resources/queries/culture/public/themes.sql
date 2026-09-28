/*---
id: culture.public.themes
description: Themes with how many published stories and places answer each
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
results:
  themeId:       { from: Theme.themeId }
  slug:          { from: Theme.slug }
  icon:          { from: Theme.icon }
  title:         { from: Theme.title }
  question:      { from: Theme.question }
  sortOrder:     { from: Theme.sortOrder }
  storyCount:    { kind: { type: numeric, precision: 9, scale: 0 } }
  locationCount: { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts:   [sortOrder, slug]
  defaultSort: { field: sortOrder, asc: true }
  key: [themeId]
---*/
SELECT
    t.{{Theme.themeId}}   AS themeId,
    t.{{Theme.slug}}      AS slug,
    t.{{Theme.icon}}      AS icon,
    t.{{Theme.title}}     AS title,
    t.{{Theme.question}}  AS question,
    t.{{Theme.sortOrder}} AS sortOrder,
    (SELECT count(*)
       FROM {{StoryTheme}} st
       JOIN {{Story}} s ON s.{{Story.storyId}} = st.{{StoryTheme.storyId}}
      WHERE st.{{StoryTheme.themeId}} = t.{{Theme.themeId}}) AS storyCount,
    (SELECT count(DISTINCT l.{{Location.locationId}})
       FROM {{StoryTheme}} lst
       JOIN {{Story}} ls ON ls.{{Story.storyId}} = lst.{{StoryTheme.storyId}}
       JOIN {{Contribution}} c ON c.{{Contribution.storyId}} = ls.{{Story.storyId}}
       JOIN {{Participant}} p ON p.{{Participant.participantId}} = c.{{Contribution.participantId}}
       JOIN {{Location}} l ON l.{{Location.locationId}} = p.{{Participant.locationId}}
      WHERE lst.{{StoryTheme.themeId}} = t.{{Theme.themeId}}) AS locationCount
FROM {{Theme}} t
