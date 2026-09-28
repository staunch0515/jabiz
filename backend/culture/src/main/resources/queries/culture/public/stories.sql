/*---
id: culture.public.stories
description: The story archive, filtered by place (of the people in it), theme and media type, and searched
access: public
cacheSeconds: 60
entities: [Story, StoryTheme, Theme, Contribution, Participant, Location]
datasets:
  Story: "urn:jabiz:dataset:public:Story"
  StoryTheme: "urn:jabiz:dataset:public:StoryTheme"
  Theme: "urn:jabiz:dataset:public:Theme"
  Contribution: "urn:jabiz:dataset:public:Contribution"
  Participant: "urn:jabiz:dataset:public:Participant"
  Location: "urn:jabiz:dataset:public:Location"
params:
  location:  { like: Location.slug, list: true, description: Stories with a perspective from one of these places }
  theme:     { like: Theme.slug, list: true, description: Stories of one of these themes }
  mediaType: { like: Story.mediaType, list: true, description: Stories of one of these media types }
  q:         { kind: { type: text, maxLength: 100 }, description: "Words (2 to 100 characters) in any language of the title, summary, body or transcript" }
  featured:  { like: Story.featured, description: Only the stories featured on the home page }
results:
  storyId:         { from: Story.storyId }
  slug:            { from: Story.slug }
  title:           { from: Story.title }
  summary:         { from: Story.summary }
  mediaType:       { from: Story.mediaType }
  storyDate:       { from: Story.storyDate }
  thumbnailFileId: { from: Story.thumbnailFileId }
  thumbnailAlt:    { from: Story.thumbnailAlt }
  featured:        { from: Story.featured }
  publishedTime:   { from: Story.publishedTime }
  locationSlugs:   { kind: { type: text } }
list:
  filters: [mediaType, featured]
  sorts:   [storyDate, publishedTime, slug]
  defaultSort: { field: storyDate, asc: false }
  key: [storyId]
---*/
-- A place is not a property of a story: a story is "from" a place when someone from there has a perspective in it.
-- locationSlugs: the places of the people in the story, comma separated, in the places' order.
-- Words shorter than 2 or longer than 100 characters find nothing; no words, no search.
SELECT
    s.{{Story.storyId}}         AS storyId,
    s.{{Story.slug}}            AS slug,
    s.{{Story.title}}           AS title,
    s.{{Story.summary}}         AS summary,
    s.{{Story.mediaType}}       AS mediaType,
    s.{{Story.storyDate}}       AS storyDate,
    s.{{Story.thumbnailFileId}} AS thumbnailFileId,
    s.{{Story.thumbnailAlt}}    AS thumbnailAlt,
    s.{{Story.featured}}        AS featured,
    s.{{Story.publishedTime}}   AS publishedTime,
    (SELECT string_agg(x.loc_slug, ',' ORDER BY x.loc_sort, x.loc_slug)
       FROM (SELECT DISTINCT sl.{{Location.slug}} AS loc_slug, sl.{{Location.sortOrder}} AS loc_sort
               FROM {{Contribution}} sc
               JOIN {{Participant}} sp ON sp.{{Participant.participantId}} = sc.{{Contribution.participantId}}
               JOIN {{Location}} sl ON sl.{{Location.locationId}} = sp.{{Participant.locationId}}
              WHERE sc.{{Contribution.storyId}} = s.{{Story.storyId}}) x) AS locationSlugs
FROM {{Story}} s
WHERE (CAST(:mediaType AS varchar[]) IS NULL OR s.{{Story.mediaType}} = ANY(:mediaType))
  AND (CAST(:featured AS boolean) IS NULL OR s.{{Story.featured}} = :featured)
  AND (CAST(:location AS varchar[]) IS NULL OR EXISTS (
        SELECT 1
          FROM {{Contribution}} c
          JOIN {{Participant}} p ON p.{{Participant.participantId}} = c.{{Contribution.participantId}}
          JOIN {{Location}} l ON l.{{Location.locationId}} = p.{{Participant.locationId}}
         WHERE c.{{Contribution.storyId}} = s.{{Story.storyId}}
           AND l.{{Location.slug}} = ANY(:location)))
  AND (CAST(:theme AS varchar[]) IS NULL OR EXISTS (
        SELECT 1
          FROM {{StoryTheme}} st
          JOIN {{Theme}} t ON t.{{Theme.themeId}} = st.{{StoryTheme.themeId}}
         WHERE st.{{StoryTheme.storyId}} = s.{{Story.storyId}}
           AND t.{{Theme.slug}} = ANY(:theme)))
  AND (coalesce(CAST(:q AS varchar), '') = ''
       OR (char_length(CAST(:q AS varchar)) BETWEEN 2 AND 100
           AND (cu_i18n_text(s.{{Story.title}}) || ' ' || cu_i18n_text(s.{{Story.summary}}) || ' '
                || cu_i18n_text(s.{{Story.body}}) || ' ' || cu_i18n_text(s.{{Story.transcript}}))
               ILIKE cu_like_pattern(CAST(:q AS varchar)) ESCAPE '\'))
