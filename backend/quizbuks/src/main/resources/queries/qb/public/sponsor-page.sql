/*---
id: qb.public.sponsor-page
description: >-
  A sponsor's published page as its link (/s/<handle>) shows it to anybody (Q60a): the headline, the cover, the website
  and the language, with the company, the logo (avatar) and the story (introduction) of the approved onboarding (Q65b);
  none when it is not published
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
  story:    { from: QbSponsor.intro }
  logo:     { from: QbSponsor.avatar }
  cover:    { from: QbSponsorPage.cover }
  website:  { from: QbSponsorPage.website }
  company:  { from: QbSponsor.company }
list:
  key: [handle]
---*/
-- The public dataset holds published pages only (its fixed scope), and the join keeps only approved sponsors'. The
-- logo and the story are the onboarding's avatar and introduction (Q65b), under the names of Q60a so that the app's
-- page reads them as before. The handle is stored lowercase: the caller lowercases what was typed.
SELECT
    p.{{QbSponsorPage.handle}}   AS handle,
    p.{{QbSponsorPage.language}} AS language,
    p.{{QbSponsorPage.headline}} AS headline,
    sp.{{QbSponsor.intro}}       AS story,
    sp.{{QbSponsor.avatar}}      AS logo,
    p.{{QbSponsorPage.cover}}    AS cover,
    p.{{QbSponsorPage.website}}  AS website,
    sp.{{QbSponsor.company}}     AS company
FROM {{QbSponsorPage}} p
JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = p.{{QbSponsorPage.userId}}
WHERE p.{{QbSponsorPage.handle}} = :handle
