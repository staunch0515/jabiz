/*---
id: qb.app.publication
description: One publication's page for a taker (U-20 – U-23) with the caller's own attempt; none when they cannot see it
entities: [QbPublicationCard, QbPublicationCounter, QbPublicationStats, QbWallet, QbCountry, QbSponsor, QbQuizVersion, QbAttempt]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStats: urn:jabiz:dataset:default:QbPublicationStats
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbCountry: urn:jabiz:dataset:default:QbCountry
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
params:
  publicationId: { like: QbPublicationCard.publicationId, required: true, description: The publication }
  at:            { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the page is for (the client's now) }
  country:       { kind: { type: text, maxLength: 2 }, description: "The taker's country (QbCountry.code)" }
results:
  publicationId:  { from: QbPublicationCard.publicationId }
  name:           { from: QbPublicationCard.name }
  quizTitle:      { from: QbPublicationCard.quizTitle }
  cover:          { from: QbPublicationCard.cover }
  intro:          { kind: { type: text, maxLength: 5000 } }
  materials:      { kind: { type: text } }
  status:         { from: QbPublicationCard.status }
  startAt:        { from: QbPublicationCard.startAt }
  endAt:          { from: QbPublicationCard.endAt }
  timeLimitSec:   { from: QbPublicationCard.timeLimitSec }
  questionCount:  { from: QbPublicationCard.questionCount }
  fullScore:      { from: QbPublicationCard.fullScore }
  passScore:      { from: QbPublicationCard.passScore }
  maxReward:      { from: QbPublicationCard.maxReward }
  sponsorName:    { from: QbSponsor.contactName }
  sponsorCompany: { from: QbSponsor.company }
  sponsorIntro:   { from: QbSponsor.intro }
  sponsorAvatar:  { from: QbSponsor.avatar }
  sponsorCountry: { from: QbPublicationCard.sponsorCountry }
  completions:    { from: QbPublicationCounter.completions }
  last1h:         { from: QbPublicationStats.last1h }
  last24h:        { from: QbPublicationStats.last24h }
  last7d:         { from: QbPublicationStats.last7d }
  last30d:        { from: QbPublicationStats.last30d }
  statsAt:        { from: QbPublicationStats.refreshedAt }
  canStart:       { kind: { type: bool } }
  myAttemptId:    { from: QbAttempt.attemptId }
  myStatus:       { from: QbAttempt.status }
  myDeadline:     { from: QbAttempt.deadline }
list:
  key: [publicationId]
permissions: [qb.play]
---*/
-- The feed's conditions (qb.app.feed) say whether the taker can start (canStart); a taker who has an attempt of it
-- reads it whether or not, to look at what they answered. The introduction and the materials come from the version's
-- snapshot, never its questions (design section 9: no correct answer before an attempt is finished). Completion
-- counts of the windows are the statistics job's (every ten minutes); the total is the counter's, live.
SELECT * FROM (
SELECT
    c.{{QbPublicationCard.publicationId}}  AS publicationId,
    c.{{QbPublicationCard.name}}           AS name,
    c.{{QbPublicationCard.quizTitle}}      AS quizTitle,
    c.{{QbPublicationCard.cover}}          AS cover,
    CAST(v.{{QbQuizVersion.content}} AS jsonb) ->> 'intro' AS intro,
    CAST(CAST(v.{{QbQuizVersion.content}} AS jsonb) -> 'materials' AS text) AS materials,
    c.{{QbPublicationCard.status}}         AS status,
    c.{{QbPublicationCard.startAt}}        AS startAt,
    c.{{QbPublicationCard.endAt}}          AS endAt,
    c.{{QbPublicationCard.timeLimitSec}}   AS timeLimitSec,
    c.{{QbPublicationCard.questionCount}}  AS questionCount,
    c.{{QbPublicationCard.fullScore}}      AS fullScore,
    c.{{QbPublicationCard.passScore}}      AS passScore,
    c.{{QbPublicationCard.maxReward}}      AS maxReward,
    sp.{{QbSponsor.contactName}}           AS sponsorName,
    sp.{{QbSponsor.company}}               AS sponsorCompany,
    sp.{{QbSponsor.intro}}                 AS sponsorIntro,
    sp.{{QbSponsor.avatar}}                AS sponsorAvatar,
    c.{{QbPublicationCard.sponsorCountry}} AS sponsorCountry,
    n.{{QbPublicationCounter.completions}} AS completions,
    coalesce(st.{{QbPublicationStats.last1h}}, 0)  AS last1h,
    coalesce(st.{{QbPublicationStats.last24h}}, 0) AS last24h,
    coalesce(st.{{QbPublicationStats.last7d}}, 0)  AS last7d,
    coalesce(st.{{QbPublicationStats.last30d}}, 0) AS last30d,
    st.{{QbPublicationStats.refreshedAt}}  AS statsAt,
    (c.{{QbPublicationCard.status}} = 'LIVE'
     AND c.{{QbPublicationCard.startAt}} <= :at AND :at < c.{{QbPublicationCard.endAt}}
     AND string_to_array(c.{{QbPublicationCard.regions}}, ',') && string_to_array(coalesce(
         (SELECT co.{{QbCountry.regions}} FROM {{QbCountry}} co WHERE co.{{QbCountry.code}} = CAST(:country AS text)),
         'GLOBAL'), ',')
     AND (c.{{QbPublicationCard.budgetCap}} IS NULL
          OR n.{{QbPublicationCounter.spent}} + n.{{QbPublicationCounter.reserved}} + c.{{QbPublicationCard.maxReward}}
             <= c.{{QbPublicationCard.budgetCap}})
     AND (c.{{QbPublicationCard.maxWinners}} IS NULL
          OR n.{{QbPublicationCounter.winners}} + n.{{QbPublicationCounter.starts}}
             - n.{{QbPublicationCounter.completions}} < c.{{QbPublicationCard.maxWinners}})
     AND coalesce(w.{{QbWallet.pendingCount}}, 0) = 0
     AND coalesce(w.{{QbWallet.balance}}, 0) >= c.{{QbPublicationCard.maxReward}}
     AND m.{{QbAttempt.attemptId}} IS NULL) AS canStart,
    m.{{QbAttempt.attemptId}}              AS myAttemptId,
    m.{{QbAttempt.status}}                 AS myStatus,
    m.{{QbAttempt.deadline}}               AS myDeadline
FROM {{QbPublicationCard}} c
JOIN {{QbPublicationCounter}} n ON n.{{QbPublicationCounter.publicationId}} = c.{{QbPublicationCard.publicationId}}
JOIN {{QbQuizVersion}} v ON v.{{QbQuizVersion.versionId}} = c.{{QbPublicationCard.versionId}}
LEFT JOIN {{QbPublicationStats}} st ON st.{{QbPublicationStats.publicationId}} = c.{{QbPublicationCard.publicationId}}
LEFT JOIN {{QbWallet}} w ON w.{{QbWallet.ownerId}} = c.{{QbPublicationCard.sponsorId}}
    AND w.{{QbWallet.kind}} = 'SPONSOR'
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = c.{{QbPublicationCard.sponsorId}}
    AND sp.{{QbSponsor.reviewStatus}} = 'APPROVED'
LEFT JOIN {{QbAttempt}} m ON m.{{QbAttempt.publicationId}} = c.{{QbPublicationCard.publicationId}}
WHERE c.{{QbPublicationCard.publicationId}} = :publicationId
) d
WHERE d.canStart OR d.myAttemptId IS NOT NULL
