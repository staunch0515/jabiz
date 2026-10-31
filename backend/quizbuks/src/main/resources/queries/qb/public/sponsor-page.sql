/*---
id: qb.public.sponsor-page
description: >-
  A sponsor's published page as its link (/s/<handle>) shows it to anybody (Q60a): the headline, the story, the logo,
  the cover, the website and the language, with the company of the approved onboarding; none when it is not published
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [QbSponsorPage, QbSponsor]
datasets:
  QbSponsorPage: urn:jabiz:dataset:public:QbSponsorPage
  QbSponsor: urn:jabiz:dataset:public:QbSponsor
params:
  handle: { like: QbSponsorPage.handle, required: true, description: The page's handle (lowercase) }
results:
  handle:   { from: QbSponsorPage.handle }
  language: { from: QbSponsorPage.language }
  headline: { from: QbSponsorPage.headline }
  story:    { from: QbSponsorPage.story }
  logo:     { from: QbSponsorPage.logo }
  cover:    { from: QbSponsorPage.cover }
  website:  { from: QbSponsorPage.website }
  company:  { from: QbSponsor.company }
list:
  key: [handle]
---*/
-- The public dataset holds published pages only (its fixed scope), and the join keeps only approved sponsors'. The
-- handle is stored lowercase: the caller lowercases what was typed.
SELECT
    p.{{QbSponsorPage.handle}}   AS handle,
    p.{{QbSponsorPage.language}} AS language,
    p.{{QbSponsorPage.headline}} AS headline,
    p.{{QbSponsorPage.story}}    AS story,
    p.{{QbSponsorPage.logo}}     AS logo,
    p.{{QbSponsorPage.cover}}    AS cover,
    p.{{QbSponsorPage.website}}  AS website,
    sp.{{QbSponsor.company}}     AS company
FROM {{QbSponsorPage}} p
JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = p.{{QbSponsorPage.userId}}
WHERE p.{{QbSponsorPage.handle}} = :handle
