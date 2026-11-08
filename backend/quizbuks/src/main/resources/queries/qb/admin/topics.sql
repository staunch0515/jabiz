/*---
id: qb.admin.topics
description: >-
  The word list of topics with its figures (F-S9), for headquarters: each word's names, whether it is offered, what it
  was merged into, and, counted on the word it stands for now (a merged word's figures are on the one it counts as),
  how many publications' cards have it (all, and live now) and how many users like it and how many of them have
  strict mode on: those two only from the minimum group up (qb.stats.min-group), else none. Custom words are never
  counted
entities: [QbTopic, QbPublicationCard, QbUserSettings, SysParam]
datasets:
  QbTopic: urn:jabiz:dataset:default:QbTopic
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbUserSettings: urn:jabiz:dataset:default:QbUserSettings
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  topicId:          { from: QbTopic.topicId }
  name:             { kind: { type: text } }
  active:           { from: QbTopic.active }
  mergedInto:       { from: QbTopic.mergedInto }
  publications:     { kind: { type: numeric, precision: 12, scale: 0 } }
  livePublications: { kind: { type: numeric, precision: 12, scale: 0 } }
  likedBy:          { kind: { type: numeric, precision: 12, scale: 0 } }
  strictBy:         { kind: { type: numeric, precision: 12, scale: 0 } }
  minGroup:         { kind: { type: numeric, precision: 9, scale: 0 } }
  createdAt:        { from: QbTopic.createdAt }
  changedAt:        { from: QbTopic.changedAt }
list:
  filters: [active]
  sorts:   [topicId, publications, livePublications, likedBy, createdAt]
  defaultSort: { field: topicId, asc: true }
  key: [topicId]
permissions: [qb.admin.topic.read]
---*/
-- The users who like a word are personal figures: below k (as every analytics template: qb.stats.min-group if a whole
-- number, else 5, never below 3) they are not given, so that a word few like does not point at a person. The
-- publications are no one's personal data and are always given. Only codes of the word list count (the cards' and
-- the settings' columns of codes, each code as the word it was merged into); a code not on the list is ignored.
WITH params AS (
    SELECT greatest(3, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,9}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 5)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.stats.min-group'),
words AS (
    SELECT w.{{QbTopic.topicId}} AS code, coalesce(w.{{QbTopic.mergedInto}}, w.{{QbTopic.topicId}}) AS target
    FROM {{QbTopic}} w),
cards AS (
    SELECT words.target,
           count(DISTINCT c.{{QbPublicationCard.publicationId}}) AS publications,
           count(DISTINCT c.{{QbPublicationCard.publicationId}})
               FILTER (WHERE c.{{QbPublicationCard.status}} = 'LIVE') AS live
    FROM {{QbPublicationCard}} c
    CROSS JOIN LATERAL unnest(string_to_array(c.{{QbPublicationCard.topics}}, ',')) AS ct(code)
    JOIN words ON words.code = ct.code
    GROUP BY words.target),
likes AS (
    SELECT words.target,
           count(DISTINCT s.{{QbUserSettings.userId}}) AS liked,
           count(DISTINCT s.{{QbUserSettings.userId}}) FILTER (WHERE s.{{QbUserSettings.strictTopics}}) AS strict
    FROM {{QbUserSettings}} s
    CROSS JOIN LATERAL unnest(string_to_array(s.{{QbUserSettings.likedTopics}}, ',')) AS l(code)
    JOIN words ON words.code = l.code
    GROUP BY words.target)
SELECT
    t.{{QbTopic.topicId}}            AS topicId,
    CAST(t.{{QbTopic.name}} AS text) AS name,
    t.{{QbTopic.active}}             AS active,
    t.{{QbTopic.mergedInto}}         AS mergedInto,
    CAST(CASE WHEN t.{{QbTopic.mergedInto}} IS NULL THEN coalesce(cards.publications, 0) END AS numeric(12, 0))
        AS publications,
    CAST(CASE WHEN t.{{QbTopic.mergedInto}} IS NULL THEN coalesce(cards.live, 0) END AS numeric(12, 0))
        AS livePublications,
    CAST(CASE WHEN t.{{QbTopic.mergedInto}} IS NULL AND coalesce(likes.liked, 0) >= params.k THEN likes.liked END
        AS numeric(12, 0)) AS likedBy,
    CAST(CASE WHEN t.{{QbTopic.mergedInto}} IS NULL AND coalesce(likes.strict, 0) >= params.k THEN likes.strict END
        AS numeric(12, 0)) AS strictBy,
    CAST(params.k AS numeric(9, 0))  AS minGroup,
    t.{{QbTopic.createdAt}}          AS createdAt,
    t.{{QbTopic.changedAt}}          AS changedAt
FROM {{QbTopic}} t
CROSS JOIN params
LEFT JOIN cards ON cards.target = t.{{QbTopic.topicId}}
LEFT JOIN likes ON likes.target = t.{{QbTopic.topicId}}
