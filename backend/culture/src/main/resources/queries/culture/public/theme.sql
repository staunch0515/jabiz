/*---
id: culture.public.theme
description: A theme page's header, its anthropological question and introduction
access: public
cacheSeconds: 60
entities: [Theme]
datasets: { Theme: "urn:jabiz:dataset:public:Theme" }
params:
  slug: { like: Theme.slug, required: true }
results:
  themeId:  { from: Theme.themeId }
  slug:     { from: Theme.slug }
  icon:     { from: Theme.icon }
  title:    { from: Theme.title }
  question: { from: Theme.question }
  intro:    { from: Theme.intro }
list:
  key: [themeId]
---*/
SELECT
    t.{{Theme.themeId}}  AS themeId,
    t.{{Theme.slug}}     AS slug,
    t.{{Theme.icon}}     AS icon,
    t.{{Theme.title}}    AS title,
    t.{{Theme.question}} AS question,
    t.{{Theme.intro}}    AS intro
FROM {{Theme}} t
WHERE t.{{Theme.slug}} = :slug
