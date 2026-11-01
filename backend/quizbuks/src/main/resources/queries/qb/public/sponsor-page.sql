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
  languages: { kind: { type: text, maxLength: 20 }, description: "Q73c: the languages to show it in, comma-separated, the preferred first (a link's ?lang=, the visitor's); the first the page offers is shown, else its main one" }
results:
  handle:   { from: QbSponsorPage.handle }
  language: { from: QbSponsorPage.language }
  headline: { from: QbSponsorPage.headline }
  story:    { from: QbSponsor.intro }
  logo:     { from: QbSponsor.avatar }
  cover:    { from: QbSponsorPage.cover }
  website:  { from: QbSponsorPage.website }
  company:  { from: QbSponsor.company }
  languages:       { from: QbSponsorPage.languages }
  displayLanguage: { from: QbSponsorPage.language }
list:
  key: [handle]
---*/
-- The public dataset holds published pages only (its fixed scope), and the join keeps only approved sponsors'. The
-- logo and the story are the onboarding's avatar and introduction (Q65b), under the names of Q60a so that the app's
-- page reads them as before. The handle is stored lowercase: the caller lowercases what was typed.
-- Q73c: the page in the first of the asked languages it offers (else its main one, the first it offers): its headline
-- and the introduction in it when translated, else its own.
SELECT
    p.{{QbSponsorPage.handle}}   AS handle,
    p.{{QbSponsorPage.language}} AS language,
    coalesce(CAST(p.{{QbSponsorPage.translations}} AS jsonb) -> dl.lang -> 'headline' ->> 'text',
        p.{{QbSponsorPage.headline}}) AS headline,
    coalesce(CAST(sp.{{QbSponsor.introTranslations}} AS jsonb) -> dl.lang -> 'intro' ->> 'text',
        sp.{{QbSponsor.intro}})  AS story,
    sp.{{QbSponsor.avatar}}      AS logo,
    p.{{QbSponsorPage.cover}}    AS cover,
    p.{{QbSponsorPage.website}}  AS website,
    sp.{{QbSponsor.company}}     AS company,
    coalesce(p.{{QbSponsorPage.languages}}, p.{{QbSponsorPage.language}}) AS languages,
    dl.lang                      AS displayLanguage
FROM {{QbSponsorPage}} p
JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = p.{{QbSponsorPage.userId}}
CROSS JOIN LATERAL (
    SELECT coalesce((SELECT u.l FROM unnest(string_to_array(CAST(:languages AS text), ',')) WITH ORDINALITY AS u(l, i)
                     WHERE u.l = ANY(string_to_array(coalesce(p.{{QbSponsorPage.languages}},
                         p.{{QbSponsorPage.language}}), ','))
                     ORDER BY u.i LIMIT 1),
        split_part(coalesce(p.{{QbSponsorPage.languages}}, p.{{QbSponsorPage.language}}), ',', 1)) AS lang) dl
WHERE p.{{QbSponsorPage.handle}} = :handle
