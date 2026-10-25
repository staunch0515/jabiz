/*---
id: qb.app.publication
description: One publication's page for a taker (U-20 – U-23) with the caller's own attempt; none when they cannot see it
entities: [QbPublicationCard, QbPublicationCounter, QbPublicationStartCounter, QbPublicationStats, QbStatsRun, QbWallet, QbCountry, QbSponsor, QbQuizVersion, QbAttempt, QbPublicationOwner, QbInvitation]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  QbPublicationStats: urn:jabiz:dataset:default:QbPublicationStats
  QbStatsRun: urn:jabiz:dataset:default:QbStatsRun
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbCountry: urn:jabiz:dataset:default:QbCountry
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
  QbPublicationOwner: urn:jabiz:dataset:me:QbPublicationOwner
  QbInvitation: urn:jabiz:dataset:me:QbInvitation
params:
  publicationId: { like: QbPublicationCard.publicationId, required: true, description: The publication }
  at:            { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the page is for (the client's now) }
  country:       { kind: { type: text, maxLength: 2 }, description: "The taker's country (QbCountry.code)" }
results:
  publicationId:  { from: QbPublicationCard.publicationId }
  name:           { from: QbPublicationCard.name }
  quizTitle:      { from: QbPublicationCard.quizTitle }
  cover:          { from: QbPublicationCard.cover }
  kind:           { from: QbPublicationCard.kind }
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
  rewardMethod:   { from: QbPublicationCard.rewardMethod }
  redeemDescription: { from: QbPublicationCard.redeemDescription }
  digitalDelivery: { from: QbPublicationCard.digitalDelivery }
  digitalDescription: { from: QbPublicationCard.digitalDescription }
  audience:       { from: QbPublicationCard.audience }
  invited:        { kind: { type: bool } }
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
  statsAt:        { from: QbStatsRun.refreshedAt }
  canStart:       { kind: { type: bool } }
  myAttemptId:    { from: QbAttempt.attemptId }
  myStatus:       { from: QbAttempt.status }
  myDeadline:     { from: QbAttempt.deadline }
list:
  key: [publicationId]
permissions: [qb.play]
---*/
-- Shown (the conditions QB_VIEW checks too): live, within its time and aimed at a region of the taker's country (for
-- an invited publication, Q25: the caller's account holds an invitation of it, claimed by QB_VIEW or the start), or
-- the caller has an attempt of it (to look at what they answered). canStart adds the feed's other conditions (winners
-- and budget not used up, a sponsor who can pay), no attempt yet, and not the caller's own publication. The
-- introduction and the materials come from the version's snapshot, never its questions (design section 9). The
-- windows' counts and their time are the statistics job's (every ten minutes); the total is the counter's, live.
-- The counters' two sides (Q10a4: starts s, completions n) are read in this one statement, as in qb.app.feed.
SELECT
    d.publicationId AS publicationId,
    d.name AS name,
    d.quizTitle AS quizTitle,
    d.cover AS cover,
    d.kind AS kind,
    d.intro AS intro,
    d.materials AS materials,
    d.status AS status,
    d.startAt AS startAt,
    d.endAt AS endAt,
    d.timeLimitSec AS timeLimitSec,
    d.questionCount AS questionCount,
    d.fullScore AS fullScore,
    d.passScore AS passScore,
    d.maxReward AS maxReward,
    d.rewardMethod AS rewardMethod,
    d.redeemDescription AS redeemDescription,
    d.digitalDelivery AS digitalDelivery,
    d.digitalDescription AS digitalDescription,
    d.audience AS audience,
    d.invited AS invited,
    d.sponsorName AS sponsorName,
    d.sponsorCompany AS sponsorCompany,
    d.sponsorIntro AS sponsorIntro,
    d.sponsorAvatar AS sponsorAvatar,
    d.sponsorCountry AS sponsorCountry,
    d.completions AS completions,
    d.last1h AS last1h,
    d.last24h AS last24h,
    d.last7d AS last7d,
    d.last30d AS last30d,
    d.statsAt AS statsAt,
    (d.shown AND d.startable AND d.myAttemptId IS NULL) AS canStart,
    d.myAttemptId AS myAttemptId,
    d.myStatus AS myStatus,
    d.myDeadline AS myDeadline
FROM (
SELECT
    c.{{QbPublicationCard.publicationId}}  AS publicationId,
    c.{{QbPublicationCard.name}}           AS name,
    c.{{QbPublicationCard.quizTitle}}      AS quizTitle,
    c.{{QbPublicationCard.cover}}          AS cover,
    c.{{QbPublicationCard.kind}}           AS kind,
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
    c.{{QbPublicationCard.rewardMethod}}   AS rewardMethod,
    c.{{QbPublicationCard.redeemDescription}} AS redeemDescription,
    c.{{QbPublicationCard.digitalDelivery}} AS digitalDelivery,
    c.{{QbPublicationCard.digitalDescription}} AS digitalDescription,
    c.{{QbPublicationCard.audience}}       AS audience,
    (inv.{{QbInvitation.invitationId}} IS NOT NULL) AS invited,
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
    sr.{{QbStatsRun.refreshedAt}}          AS statsAt,
    (c.{{QbPublicationCard.status}} = 'LIVE'
     AND c.{{QbPublicationCard.startAt}} <= :at AND :at < c.{{QbPublicationCard.endAt}}
     AND CASE WHEN c.{{QbPublicationCard.audience}} = 'INVITED' THEN inv.{{QbInvitation.invitationId}} IS NOT NULL
         ELSE string_to_array(c.{{QbPublicationCard.regions}}, ',') && string_to_array(coalesce(
         (SELECT co.{{QbCountry.regions}} FROM {{QbCountry}} co WHERE co.{{QbCountry.code}} = CAST(:country AS text)),
         'GLOBAL'), ',') END) AS shown,
    (c.{{QbPublicationCard.budgetCap}} IS NULL
     OR n.{{QbPublicationCounter.spent}} + s.{{QbPublicationStartCounter.reservedTotal}}
        - n.{{QbPublicationCounter.released}} + c.{{QbPublicationCard.maxReward}} <= c.{{QbPublicationCard.budgetCap}})
    AND (c.{{QbPublicationCard.maxWinners}} IS NULL
         OR n.{{QbPublicationCounter.winners}} + s.{{QbPublicationStartCounter.starts}}
            - n.{{QbPublicationCounter.completions}} < c.{{QbPublicationCard.maxWinners}})
    AND (c.{{QbPublicationCard.rewardMethod}} <> 'PLATFORM'
         OR (coalesce(w.{{QbWallet.pendingCount}}, 0) = 0
             AND coalesce(w.{{QbWallet.balance}} - w.{{QbWallet.held}}, 0) >= c.{{QbPublicationCard.maxReward}}))
    AND own.{{QbPublicationOwner.publicationId}} IS NULL AS startable,
    m.{{QbAttempt.attemptId}}              AS myAttemptId,
    m.{{QbAttempt.status}}                 AS myStatus,
    m.{{QbAttempt.deadline}}               AS myDeadline
FROM {{QbPublicationCard}} c
JOIN {{QbPublicationCounter}} n ON n.{{QbPublicationCounter.publicationId}} = c.{{QbPublicationCard.publicationId}}
JOIN {{QbPublicationStartCounter}} s
    ON s.{{QbPublicationStartCounter.publicationId}} = c.{{QbPublicationCard.publicationId}}
JOIN {{QbQuizVersion}} v ON v.{{QbQuizVersion.versionId}} = c.{{QbPublicationCard.versionId}}
LEFT JOIN {{QbPublicationStats}} st ON st.{{QbPublicationStats.publicationId}} = c.{{QbPublicationCard.publicationId}}
LEFT JOIN {{QbStatsRun}} sr ON sr.{{QbStatsRun.job}} = 'qb.publication-stats'
LEFT JOIN {{QbWallet}} w ON w.{{QbWallet.ownerId}} = c.{{QbPublicationCard.sponsorId}}
    AND w.{{QbWallet.kind}} = 'SPONSOR'
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = c.{{QbPublicationCard.sponsorId}}
    AND sp.{{QbSponsor.reviewStatus}} = 'APPROVED'
LEFT JOIN {{QbAttempt}} m ON m.{{QbAttempt.publicationId}} = c.{{QbPublicationCard.publicationId}}
LEFT JOIN {{QbPublicationOwner}} own
    ON own.{{QbPublicationOwner.publicationId}} = c.{{QbPublicationCard.publicationId}}
LEFT JOIN {{QbInvitation}} inv ON inv.{{QbInvitation.publicationId}} = c.{{QbPublicationCard.publicationId}}
WHERE c.{{QbPublicationCard.publicationId}} = :publicationId
) d
WHERE d.shown OR d.myAttemptId IS NOT NULL
