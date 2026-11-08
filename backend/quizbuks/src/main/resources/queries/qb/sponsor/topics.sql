/*---
id: qb.sponsor.topics
description: >-
  The word list of topics for the topics of the sponsor's quizzes and publications (F-S9): every word with its names, whether it is still offered and what it was merged into
  (a word deactivated or merged is not chosen anew, but is still shown where it was chosen; a merged one by the name of
  the one it counts as)
entities: [QbTopic]
datasets:
  QbTopic: urn:jabiz:dataset:default:QbTopic
results:
  topicId:    { from: QbTopic.topicId }
  name:       { kind: { type: text } }
  active:     { from: QbTopic.active }
  mergedInto: { from: QbTopic.mergedInto }
list:
  filters: [active]
  sorts:   [topicId]
  defaultSort: { field: topicId, asc: true }
  key: [topicId]
permissions: [qb.content.write]
---*/
SELECT
    t.{{QbTopic.topicId}}            AS topicId,
    CAST(t.{{QbTopic.name}} AS text) AS name,
    t.{{QbTopic.active}}             AS active,
    t.{{QbTopic.mergedInto}}         AS mergedInto
FROM {{QbTopic}} t
