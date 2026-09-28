/*---
id: culture.public.search
description: Stories, participants, themes and resources whose texts in any language contain the words
access: public
cacheSeconds: 60
entities: [Story, Participant, Theme, Resource]
datasets:
  Story: "urn:jabiz:dataset:public:Story"
  Participant: "urn:jabiz:dataset:public:Participant"
  Theme: "urn:jabiz:dataset:public:Theme"
  Resource: "urn:jabiz:dataset:public:Resource"
params:
  q: { kind: { type: text, maxLength: 100 }, required: true, description: "Words, 2 to 100 characters" }
results:
  kind:        { kind: { type: text, maxLength: 16 } }
  slug:        { kind: { type: text, maxLength: 80 } }
  title:       { from: Story.title }
  name:        { from: Participant.displayName }
  summary:     { from: Story.summary }
  imageFileId: { from: Story.thumbnailFileId }
list:
  filters: [kind]
  sorts:   [kind, slug]
  defaultSort: { field: kind, asc: true }
  key: [kind, slug]
---*/
-- kind says what was found: STORY, PERSON, THEME or RESOURCE. title: of a story, theme or resource (none for a person); name: of a person (none
-- otherwise); summary: a story's summary, a person's short bio, a theme's question or a resource's description;
-- imageFileId: a story's thumbnail or a person's portrait.
-- The same text expressions as the trigram indexes of V4__culture_search.sql, so that the indexes are used.
-- Words shorter than 2 or longer than 100 characters find nothing.
SELECT 'STORY' AS kind, s.{{Story.slug}} AS slug, s.{{Story.title}} AS title, CAST(NULL AS varchar) AS name,
       s.{{Story.summary}} AS summary, s.{{Story.thumbnailFileId}} AS imageFileId
  FROM {{Story}} s
 WHERE char_length(CAST(:q AS varchar)) BETWEEN 2 AND 100
   AND (cu_i18n_text(s.{{Story.title}}) || ' ' || cu_i18n_text(s.{{Story.summary}}) || ' '
        || cu_i18n_text(s.{{Story.body}}) || ' ' || cu_i18n_text(s.{{Story.transcript}}))
       ILIKE cu_like_pattern(CAST(:q AS varchar)) ESCAPE '\'
UNION ALL
SELECT 'PERSON', p.{{Participant.slug}}, NULL, p.{{Participant.displayName}},
       p.{{Participant.shortBio}}, p.{{Participant.portraitFileId}}
  FROM {{Participant}} p
 WHERE char_length(CAST(:q AS varchar)) BETWEEN 2 AND 100
   AND (p.{{Participant.displayName}} || ' ' || cu_i18n_text(p.{{Participant.shortBio}}) || ' '
        || cu_i18n_text(p.{{Participant.bio}}))
       ILIKE cu_like_pattern(CAST(:q AS varchar)) ESCAPE '\'
UNION ALL
SELECT 'THEME', t.{{Theme.slug}}, t.{{Theme.title}}, NULL, t.{{Theme.question}}, NULL
  FROM {{Theme}} t
 WHERE char_length(CAST(:q AS varchar)) BETWEEN 2 AND 100
   AND (cu_i18n_text(t.{{Theme.title}}) || ' ' || cu_i18n_text(t.{{Theme.question}}))
       ILIKE cu_like_pattern(CAST(:q AS varchar)) ESCAPE '\'
UNION ALL
SELECT 'RESOURCE', r.{{Resource.slug}}, r.{{Resource.title}}, NULL, r.{{Resource.description}}, NULL
  FROM {{Resource}} r
 WHERE char_length(CAST(:q AS varchar)) BETWEEN 2 AND 100
   AND (cu_i18n_text(r.{{Resource.title}}) || ' ' || cu_i18n_text(r.{{Resource.description}}))
       ILIKE cu_like_pattern(CAST(:q AS varchar)) ESCAPE '\'
