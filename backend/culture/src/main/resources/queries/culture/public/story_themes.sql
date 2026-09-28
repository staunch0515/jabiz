/*---
id: culture.public.story_themes
description: The themes of a published story
access: public
cacheSeconds: 60
entities: [Theme, StoryTheme, Story]
datasets:
  Theme: "urn:jabiz:dataset:public:Theme"
  StoryTheme: "urn:jabiz:dataset:public:StoryTheme"
  Story: "urn:jabiz:dataset:public:Story"
params:
  slug: { like: Story.slug, required: true }
results:
  themeId:   { from: Theme.themeId }
  slug:      { from: Theme.slug }
  icon:      { from: Theme.icon }
  title:     { from: Theme.title }
  sortOrder: { from: Theme.sortOrder }
list:
  sorts:   [sortOrder]
  defaultSort: { field: sortOrder, asc: true }
  key: [themeId]
---*/
SELECT
    t.{{Theme.themeId}}   AS themeId,
    t.{{Theme.slug}}      AS slug,
    t.{{Theme.icon}}      AS icon,
    t.{{Theme.title}}     AS title,
    t.{{Theme.sortOrder}} AS sortOrder
FROM {{Theme}} t
JOIN {{StoryTheme}} st ON st.{{StoryTheme.themeId}} = t.{{Theme.themeId}}
JOIN {{Story}} s ON s.{{Story.storyId}} = st.{{StoryTheme.storyId}}
WHERE s.{{Story.slug}} = :slug
