/*---
id: qb.app.feed
description: The publications a taker can answer now (U-10 – U-14), newest first; searched by name and title
entities: [QbPublicationCard, QbPublicationCounter, QbPublicationStartCounter, QbWallet, QbCountry, QbSponsor, QbSponsorPage, QbPublicationOwner, QbAttempt, QbProfileAttribute, QbPublicationCriterion, QbCertificationRound, QbCertificationRecord]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbCountry: urn:jabiz:dataset:default:QbCountry
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorPage: urn:jabiz:dataset:public:QbSponsorPage
  QbPublicationOwner: urn:jabiz:dataset:me:QbPublicationOwner
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
  QbProfileAttribute: urn:jabiz:dataset:me:QbProfileAttribute
  QbPublicationCriterion: urn:jabiz:dataset:default:QbPublicationCriterion
  QbCertificationRound: urn:jabiz:dataset:default:QbCertificationRound
  QbCertificationRecord: urn:jabiz:dataset:me:QbCertificationRecord
params:
  at:      { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the feed is for (the client's now) }
  country: { kind: { type: text, maxLength: 2 }, description: "The taker's country (QbCountry.code); without one only publications aimed at GLOBAL" }
  q:       { kind: { type: text, maxLength: 100 }, description: Only publications whose name or quiz title contains this text }
  includeAnswered: { kind: { type: bool }, description: "If true, also the publications the caller has answered (Q34); by default those are left out" }
  method:  { like: QbPublicationCard.rewardMethod, description: "Only publications whose reward comes this way (Q22, Q33): PLATFORM, SPONSOR_CASH, OFFLINE_REDEEM or DIGITAL_PRIZE" }
  languages: { kind: { type: text, maxLength: 20 }, description: "Only publications offering one of these languages (Q59a, Q73b), comma-separated in the taker's order (ja, zh, en): their switches, or their interface language; each shown in the first of them it offers; all when not given" }
results:
  publicationId:  { from: QbPublicationCard.publicationId }
  name:           { from: QbPublicationCard.name }
  quizTitle:      { from: QbPublicationCard.quizTitle }
  cover:          { from: QbPublicationCard.cover }
  video:          { from: QbPublicationCard.video }
  watchRequired:  { from: QbPublicationCard.watchRequired }
  videoDurationSec: { from: QbPublicationCard.videoDurationSec }
  kind:           { from: QbPublicationCard.kind }
  language:       { from: QbPublicationCard.language }
  languages:      { from: QbPublicationCard.languages }
  displayLanguage: { kind: { type: text, maxLength: 2 } }
  referralDescription: { kind: { type: text, maxLength: 500 } }
  maxReward:      { from: QbPublicationCard.maxReward }
  rewardMethod:   { from: QbPublicationCard.rewardMethod }
  digitalDelivery: { from: QbPublicationCard.digitalDelivery }
  digitalDescription: { from: QbPublicationCard.digitalDescription }
  referral:       { from: QbPublicationCard.referral }
  completions:    { from: QbPublicationCounter.completions }
  sponsorName:    { from: QbSponsor.contactName }
  sponsorCompany: { from: QbSponsor.company }
  sponsorAvatar:  { from: QbSponsor.avatar }
  sponsorHandle:  { from: QbSponsorPage.handle }
  sponsorCountry: { from: QbPublicationCard.sponsorCountry }
  startAt:        { from: QbPublicationCard.startAt }
  endAt:          { from: QbPublicationCard.endAt }
  timeLimitSec:   { from: QbPublicationCard.timeLimitSec }
  questionCount:  { from: QbPublicationCard.questionCount }
  fullScore:      { from: QbPublicationCard.fullScore }
  myAttemptId:    { from: QbAttempt.attemptId }
  myStatus:       { from: QbAttempt.status }
  myDeadline:     { from: QbAttempt.deadline }
list:
  filters: [sponsorCountry, sponsorName, sponsorCompany, maxReward, rewardMethod, completions, startAt]
  sorts:   [startAt, maxReward, completions]
  defaultSort: { field: startAt, asc: false }
  key: [publicationId]
permissions: [qb.play]
---*/
-- The conditions of play.AttemptRules.startRefusal (D-Q5-6), which QB_ATTEMPT_START checks again by the server's
-- clock: live and within its time; aimed at a region of the taker's country (every country is in GLOBAL); budget and
-- winners not used up, attempts in progress counting with the highest reward and a winner's place each; a sponsor
-- with nothing pending and a balance that pays the highest reward (no wallet row is a balance of 0; only for rewards
-- paid from the wallet, Q22: cash and prizes are the sponsor's own affair); not the caller's
-- own (QbPublicationOwner: the cards of the caller's publications). Only ordinary
-- tables but the countries and the sponsors, both small. The sponsor's name and avatar are an approved onboarding's
-- only (Q2 review). The text is found as written: strpos takes it literally, so % _ and \ match themselves.
-- The counters are two rows (Q10a4): the starts' side s and the completions' side n, read here in one statement, so
-- that they come from one snapshot (never fewer starts than completions): in progress s.starts - n.completions,
-- reserved s.reservedTotal - n.released.
SELECT
    c.{{QbPublicationCard.publicationId}}  AS publicationId,
    -- Q73b: the texts in the language shown (the card's translations), else the card's own.
    coalesce(CAST(c.{{QbPublicationCard.translations}} AS jsonb) -> dl.lang ->> 'name',
             c.{{QbPublicationCard.name}}) AS name,
    coalesce(CAST(c.{{QbPublicationCard.translations}} AS jsonb) -> dl.lang ->> 'quizTitle',
             c.{{QbPublicationCard.quizTitle}}) AS quizTitle,
    c.{{QbPublicationCard.cover}}          AS cover,
    c.{{QbPublicationCard.video}}          AS video,
    -- Q75: whether that video must be watched before starting, and how long it is.
    c.{{QbPublicationCard.watchRequired}}  AS watchRequired,
    c.{{QbPublicationCard.videoDurationSec}} AS videoDurationSec,
    c.{{QbPublicationCard.kind}}           AS kind,
    c.{{QbPublicationCard.language}}       AS language,
    c.{{QbPublicationCard.languages}}      AS languages,
    dl.lang                                AS displayLanguage,
    CAST(c.{{QbPublicationCard.translations}} AS jsonb) -> dl.lang ->> 'referralDescription' AS referralDescription,
    c.{{QbPublicationCard.maxReward}}      AS maxReward,
    c.{{QbPublicationCard.rewardMethod}}   AS rewardMethod,
    c.{{QbPublicationCard.digitalDelivery}} AS digitalDelivery,
    coalesce(CAST(c.{{QbPublicationCard.translations}} AS jsonb) -> dl.lang ->> 'digitalDescription',
             c.{{QbPublicationCard.digitalDescription}}) AS digitalDescription,
    c.{{QbPublicationCard.referral}}       AS referral,
    n.{{QbPublicationCounter.completions}} AS completions,
    sp.{{QbSponsor.contactName}}           AS sponsorName,
    sp.{{QbSponsor.company}}               AS sponsorCompany,
    sp.{{QbSponsor.avatar}}                AS sponsorAvatar,
    -- Q64: the sponsor's published page (the public view holds published ones only), which the card links to.
    sp_page.{{QbSponsorPage.handle}}       AS sponsorHandle,
    c.{{QbPublicationCard.sponsorCountry}} AS sponsorCountry,
    c.{{QbPublicationCard.startAt}}        AS startAt,
    c.{{QbPublicationCard.endAt}}          AS endAt,
    c.{{QbPublicationCard.timeLimitSec}}   AS timeLimitSec,
    c.{{QbPublicationCard.questionCount}}  AS questionCount,
    c.{{QbPublicationCard.fullScore}}      AS fullScore,
    -- Q64: the caller's own attempt (the view holds theirs only, one a publication), for the card's start to say
    -- whether it starts, resumes or shows the result.
    m.{{QbAttempt.attemptId}}              AS myAttemptId,
    m.{{QbAttempt.status}}                 AS myStatus,
    m.{{QbAttempt.deadline}}               AS myDeadline
FROM {{QbPublicationCard}} c
-- Q73b: the language the card is shown in: the first of the taker's it offers, else its main one.
CROSS JOIN LATERAL (
    SELECT coalesce((SELECT u.l FROM unnest(string_to_array(CAST(:languages AS text), ',')) WITH ORDINALITY AS u(l, i)
                     WHERE u.l = ANY(string_to_array(coalesce(c.{{QbPublicationCard.languages}},
                                                              c.{{QbPublicationCard.language}}), ','))
                     ORDER BY u.i LIMIT 1), c.{{QbPublicationCard.language}}) AS lang) dl
JOIN {{QbPublicationCounter}} n ON n.{{QbPublicationCounter.publicationId}} = c.{{QbPublicationCard.publicationId}}
JOIN {{QbPublicationStartCounter}} s
    ON s.{{QbPublicationStartCounter.publicationId}} = c.{{QbPublicationCard.publicationId}}
LEFT JOIN {{QbWallet}} w ON w.{{QbWallet.ownerId}} = c.{{QbPublicationCard.publisherId}}
    AND w.{{QbWallet.kind}} = 'SPONSOR'
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = c.{{QbPublicationCard.publisherId}}
    AND sp.{{QbSponsor.reviewStatus}} = 'APPROVED'
LEFT JOIN {{QbSponsorPage}} sp_page ON sp_page.{{QbSponsorPage.userId}} = sp.{{QbSponsor.userId}}
LEFT JOIN {{QbAttempt}} m ON m.{{QbAttempt.publicationId}} = c.{{QbPublicationCard.publicationId}}
WHERE c.{{QbPublicationCard.status}} = 'LIVE'
  -- S0 (D-S0-4): the App lists the sponsors' publications; a shop's (S2) are answered in its storefront.
  AND c.{{QbPublicationCard.publisherKind}} = 'SPONSOR'
  -- Q25: an invited publication is reached from its mail only, never listed.
  AND c.{{QbPublicationCard.audience}} = 'PUBLIC'
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
  AND (c.{{QbPublicationCard.rewardMethod}} <> 'PLATFORM'
       OR (coalesce(w.{{QbWallet.pendingCount}}, 0) = 0
           AND coalesce(w.{{QbWallet.balance}} - w.{{QbWallet.held}}, 0) >= c.{{QbPublicationCard.maxReward}}))
  AND (CAST(:method AS text) IS NULL OR c.{{QbPublicationCard.rewardMethod}} = CAST(:method AS text))
  -- Q59a: publications of different languages are not listed together; the client says which the taker sees. Q73b:
  -- one offering several is listed when it offers one of them, once, in the first of them it offers.
  AND (CAST(:languages AS text) IS NULL
       OR string_to_array(coalesce(c.{{QbPublicationCard.languages}}, c.{{QbPublicationCard.language}}), ',')
          && string_to_array(CAST(:languages AS text), ','))
  -- Q34: what the caller answered is done, and so is an attempt whose time ran out before the expiry job closed it;
  -- one still in progress stays, so that it can be resumed from here. The view holds the caller's own attempts only
  -- (taker), found by qb_attempt_once (user_id, publication_id).
  AND (CAST(:includeAnswered AS boolean) IS TRUE
       OR NOT EXISTS (SELECT 1 FROM {{QbAttempt}} a
                      WHERE a.{{QbAttempt.publicationId}} = c.{{QbPublicationCard.publicationId}}
                        AND (a.{{QbAttempt.status}} <> 'IN_PROGRESS' OR a.{{QbAttempt.deadline}} <= :at)))
  -- Q47: the caller's profile meets every condition of the publication (qb.system.audience-count, the same; the
  -- view holds the caller's own attributes only).
  AND NOT EXISTS (
      SELECT 1 FROM {{QbPublicationCriterion}} pc
      WHERE pc.{{QbPublicationCriterion.publicationId}} = c.{{QbPublicationCard.publicationId}}
      GROUP BY pc.{{QbPublicationCriterion.criterionKey}}
      HAVING NOT bool_or(EXISTS (
          SELECT 1 FROM {{QbProfileAttribute}} pa
          WHERE pa.{{QbProfileAttribute.criterionKey}} = pc.{{QbPublicationCriterion.criterionKey}}
            AND (pa.{{QbProfileAttribute.valueCode}} = pc.{{QbPublicationCriterion.valueCode}}
                 OR (pc.{{QbPublicationCriterion.minValue}} IS NOT NULL
                     AND CASE pc.{{QbPublicationCriterion.measure}}
                             WHEN 'YEARS_SINCE' THEN EXTRACT(YEAR FROM CAST(:at AS timestamptz) AT TIME ZONE 'UTC')
                                 - pa.{{QbProfileAttribute.valueNumber}}
                             ELSE pa.{{QbProfileAttribute.valueNumber}} END
                         BETWEEN pc.{{QbPublicationCriterion.minValue}} AND pc.{{QbPublicationCriterion.maxValue}})))))
  -- Q70a (D-Q70-1, option B): a certification's round is not listed to whoever holds the certification now. The
  -- holding is CertificationHolding's rule over the caller's records and the passes not recorded yet (RoundHolders, the
  -- same; the catalog's versions and validity as Certifications, CertificationTemplatesTest), as in qb.app.publication.
  AND (SELECT max(e.until)
       FROM {{QbCertificationRound}} cr
       JOIN (VALUES ('SPONSOR_APPLY', 1, 31536000), ('REFERRER', 1, 31536000)) AS cat(cert, ver, secs)
           ON cat.cert = cr.{{QbCertificationRound.certification}}
       CROSS JOIN LATERAL (
           SELECT rec.{{QbCertificationRecord.certificationVersion}} AS ver, rec.{{QbCertificationRecord.at}} AS at,
                  rec.{{QbCertificationRecord.validUntil}} AS until
           FROM {{QbCertificationRecord}} rec
           WHERE rec.{{QbCertificationRecord.certification}} = cr.{{QbCertificationRound.certification}}
             AND rec.{{QbCertificationRecord.kind}} IN ('PASSED', 'GRANTED')
           UNION ALL
           SELECT r2.{{QbCertificationRound.certificationVersion}}, a.{{QbAttempt.submittedAt}},
                  a.{{QbAttempt.submittedAt}} + make_interval(secs => cat.secs)
           FROM {{QbCertificationRound}} r2
           JOIN {{QbAttempt}} a ON CAST(a.{{QbAttempt.publicationId}} AS text) = r2.{{QbCertificationRound.publicationId}}
           WHERE r2.{{QbCertificationRound.certification}} = cr.{{QbCertificationRound.certification}}
             AND a.{{QbAttempt.passed}} AND a.{{QbAttempt.submittedAt}} IS NOT NULL
             AND (r2.{{QbCertificationRound.closedAt}} IS NULL
                  OR a.{{QbAttempt.submittedAt}} < r2.{{QbCertificationRound.closedAt}})) e
       WHERE cr.{{QbCertificationRound.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
         AND e.ver = cat.ver AND e.until > :at
         AND e.at > coalesce((SELECT max(rv.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} rv
                              WHERE rv.{{QbCertificationRecord.certification}} = cr.{{QbCertificationRound.certification}}
                                AND rv.{{QbCertificationRecord.kind}} = 'REVOKED'), '-infinity')) IS NULL
  AND NOT EXISTS (SELECT 1 FROM {{QbPublicationOwner}} own
                  WHERE own.{{QbPublicationOwner.publicationId}} = c.{{QbPublicationCard.publicationId}})
  AND (CAST(:q AS text) IS NULL
       OR strpos(lower(c.{{QbPublicationCard.name}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(c.{{QbPublicationCard.quizTitle}}), lower(CAST(:q AS text))) > 0)
