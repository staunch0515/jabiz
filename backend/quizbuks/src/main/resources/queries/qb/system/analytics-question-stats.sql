/*---
id: qb.system.analytics-question-stats
description: The shown question statistics of some publications, for the analytics job to update
entities: [QbQuestionStat]
datasets:
  QbQuestionStat: urn:jabiz:dataset:default:QbQuestionStat
params:
  ids: { like: QbQuestionStat.publicationId, list: true, required: true, description: The publications }
results:
  statId:        { from: QbQuestionStat.statId }
  publicationId: { from: QbQuestionStat.publicationId }
  questionNo:    { from: QbQuestionStat.questionNo }
  completions:   { from: QbQuestionStat.completions }
  correct:       { from: QbQuestionStat.correct }
  version:       { from: QbQuestionStat.version }
list:
  key: [statId]
permissions: [qb.stats.system]
---*/
-- Through the unique (publication, question) index.
SELECT
    s.{{QbQuestionStat.statId}}        AS statId,
    s.{{QbQuestionStat.publicationId}} AS publicationId,
    s.{{QbQuestionStat.questionNo}}    AS questionNo,
    s.{{QbQuestionStat.completions}}   AS completions,
    s.{{QbQuestionStat.correct}}       AS correct,
    s.{{QbQuestionStat.version}}       AS version
FROM {{QbQuestionStat}} s
WHERE s.{{QbQuestionStat.publicationId}} = ANY(:ids)
