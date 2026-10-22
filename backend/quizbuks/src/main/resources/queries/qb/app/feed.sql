/*---
id: qb.app.feed
description: The publications a taker can answer now (U-10 – U-14), newest first; searched by name and title
entities: [QbPublicationCard, QbPublicationCounter, QbPublicationStartCounter, QbWallet, QbCountry, QbSponsor, QbPublicationOwner]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbCountry: urn:jabiz:dataset:default:QbCountry
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbPublicationOwner: urn:jabiz:dataset:me:QbPublicationOwner
params:
  at:      { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the feed is for (the client's now) }
  country: { kind: { type: text, maxLength: 2 }, description: "The taker's country (QbCountry.code); without one only publications aimed at GLOBAL" }
  q:       { kind: { type: text, maxLength: 100 }, description: Only publications whose name or quiz title contains this text }
results:
  publicationId:  { from: QbPublicationCard.publicationId }
  name:           { from: QbPublicationCard.name }
  quizTitle:      { from: QbPublicationCard.quizTitle }
  cover:          { from: QbPublicationCard.cover }
  kind:           { from: QbPublicationCard.kind }
  maxReward:      { from: QbPublicationCard.maxReward }
  completions:    { from: QbPublicationCounter.completions }
  sponsorName:    { from: QbSponsor.contactName }
  sponsorCompany: { from: QbSponsor.company }
  sponsorAvatar:  { from: QbSponsor.avatar }
  sponsorCountry: { from: QbPublicationCard.sponsorCountry }
  startAt:        { from: QbPublicationCard.startAt }
  endAt:          { from: QbPublicationCard.endAt }
  timeLimitSec:   { from: QbPublicationCard.timeLimitSec }
  questionCount:  { from: QbPublicationCard.questionCount }
  fullScore:      { from: QbPublicationCard.fullScore }
list:
  filters: [sponsorCountry, sponsorName, sponsorCompany, maxReward, completions, startAt]
  sorts:   [startAt, maxReward, completions]
  defaultSort: { field: startAt, asc: false }
  key: [publicationId]
permissions: [qb.play]
---*/
-- The conditions of play.AttemptRules.startRefusal (D-Q5-6), which QB_ATTEMPT_START checks again by the server's
-- clock: live and within its time; aimed at a region of the taker's country (every country is in GLOBAL); budget and
-- winners not used up, attempts in progress counting with the highest reward and a winner's place each; a sponsor
-- with nothing pending and a balance that pays the highest reward (no wallet row is a balance of 0); not the caller's
-- own (QbPublicationOwner: the cards of the caller's publications). Only ordinary
-- tables but the countries and the sponsors, both small. The sponsor's name and avatar are an approved onboarding's
-- only (Q2 review). The text is found as written: strpos takes it literally, so % _ and \ match themselves.
-- The counters are two rows (Q10a4): the starts' side s and the completions' side n, read here in one statement, so
-- that they come from one snapshot (never fewer starts than completions): in progress s.starts - n.completions,
-- reserved s.reservedTotal - n.released.
SELECT
    c.{{QbPublicationCard.publicationId}}  AS publicationId,
    c.{{QbPublicationCard.name}}           AS name,
    c.{{QbPublicationCard.quizTitle}}      AS quizTitle,
    c.{{QbPublicationCard.cover}}          AS cover,
    c.{{QbPublicationCard.kind}}           AS kind,
    c.{{QbPublicationCard.maxReward}}      AS maxReward,
    n.{{QbPublicationCounter.completions}} AS completions,
    sp.{{QbSponsor.contactName}}           AS sponsorName,
    sp.{{QbSponsor.company}}               AS sponsorCompany,
    sp.{{QbSponsor.avatar}}                AS sponsorAvatar,
    c.{{QbPublicationCard.sponsorCountry}} AS sponsorCountry,
    c.{{QbPublicationCard.startAt}}        AS startAt,
    c.{{QbPublicationCard.endAt}}          AS endAt,
    c.{{QbPublicationCard.timeLimitSec}}   AS timeLimitSec,
    c.{{QbPublicationCard.questionCount}}  AS questionCount,
    c.{{QbPublicationCard.fullScore}}      AS fullScore
FROM {{QbPublicationCard}} c
JOIN {{QbPublicationCounter}} n ON n.{{QbPublicationCounter.publicationId}} = c.{{QbPublicationCard.publicationId}}
JOIN {{QbPublicationStartCounter}} s
    ON s.{{QbPublicationStartCounter.publicationId}} = c.{{QbPublicationCard.publicationId}}
LEFT JOIN {{QbWallet}} w ON w.{{QbWallet.ownerId}} = c.{{QbPublicationCard.sponsorId}}
    AND w.{{QbWallet.kind}} = 'SPONSOR'
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = c.{{QbPublicationCard.sponsorId}}
    AND sp.{{QbSponsor.reviewStatus}} = 'APPROVED'
WHERE c.{{QbPublicationCard.status}} = 'LIVE'
  AND c.{{QbPublicationCard.startAt}} <= :at AND :at < c.{{QbPublicationCard.endAt}}
  AND string_to_array(c.{{QbPublicationCard.regions}}, ',') && string_to_array(coalesce(
      (SELECT co.{{QbCountry.regions}} FROM {{QbCountry}} co WHERE co.{{QbCountry.code}} = CAST(:country AS text)),
      'GLOBAL'), ',')
  AND (c.{{QbPublicationCard.budgetCap}} IS NULL
       OR n.{{QbPublicationCounter.spent}} + s.{{QbPublicationStartCounter.reservedTotal}}
          - n.{{QbPublicationCounter.released}} + c.{{QbPublicationCard.maxReward}} <= c.{{QbPublicationCard.budgetCap}})
  AND (c.{{QbPublicationCard.maxWinners}} IS NULL
       OR n.{{QbPublicationCounter.winners}} + s.{{QbPublicationStartCounter.starts}}
          - n.{{QbPublicationCounter.completions}} < c.{{QbPublicationCard.maxWinners}})
  AND coalesce(w.{{QbWallet.pendingCount}}, 0) = 0
  AND coalesce(w.{{QbWallet.balance}} - w.{{QbWallet.held}}, 0) >= c.{{QbPublicationCard.maxReward}}
  AND NOT EXISTS (SELECT 1 FROM {{QbPublicationOwner}} own
                  WHERE own.{{QbPublicationOwner.publicationId}} = c.{{QbPublicationCard.publicationId}})
  AND (CAST(:q AS text) IS NULL
       OR strpos(lower(c.{{QbPublicationCard.name}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(c.{{QbPublicationCard.quizTitle}}), lower(CAST(:q AS text))) > 0)
