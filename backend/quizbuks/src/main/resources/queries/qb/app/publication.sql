/*---
id: qb.app.publication
description: One publication's page for a taker (U-20 – U-23) with the caller's own attempt; none when they cannot see it
entities: [QbRegionException, QbPublicationCard, QbPublicationCounter, QbPublicationStartCounter, QbPublicationStats, QbStatsRun, QbWallet, QbCountry, QbSponsor, QbSponsorPage, QbQuizVersion, QbAttempt, QbPublicationOwner, QbInvitation, QbProfileAttribute, QbPublicationCriterion, QbCertificationRound, QbCertificationRecord, QbReferral, QbPublicationPrerequisite, QbSponsorCertification]
datasets:
  QbRegionException: urn:jabiz:dataset:public:QbRegionException
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  QbPublicationStats: urn:jabiz:dataset:default:QbPublicationStats
  QbStatsRun: urn:jabiz:dataset:default:QbStatsRun
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbCountry: urn:jabiz:dataset:default:QbCountry
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorPage: urn:jabiz:dataset:public:QbSponsorPage
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
  QbProfileAttribute: urn:jabiz:dataset:me:QbProfileAttribute
  QbPublicationCriterion: urn:jabiz:dataset:default:QbPublicationCriterion
  QbPublicationPrerequisite: urn:jabiz:dataset:default:QbPublicationPrerequisite
  QbCertificationRound: urn:jabiz:dataset:default:QbCertificationRound
  QbSponsorCertification: urn:jabiz:dataset:default:QbSponsorCertification
  QbCertificationRecord: urn:jabiz:dataset:me:QbCertificationRecord
  QbReferral: urn:jabiz:dataset:referee:QbReferral
  QbPublicationOwner: urn:jabiz:dataset:me:QbPublicationOwner
  QbInvitation: urn:jabiz:dataset:me:QbInvitation
params:
  publicationId: { like: QbPublicationCard.publicationId, required: true, description: The publication }
  at:            { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the page is for (the client's now) }
  country:       { kind: { type: text, maxLength: 2 }, description: "The taker's country (QbCountry.code)" }
  mainlandChina: { kind: { type: bool }, description: "F-S11: whether the caller's address is in mainland China, as GET /api/client/region said; publications with a foreign video and no exception are then not for them" }
  languages:     { kind: { type: text, maxLength: 20 }, description: "Q73b: the languages to show it in, in the taker's order (a link's first); the first one it offers is shown, else its main one; an attempt shows its own" }
results:
  publicationId:  { from: QbPublicationCard.publicationId }
  name:           { from: QbPublicationCard.name }
  quizTitle:      { from: QbPublicationCard.quizTitle }
  cover:          { from: QbPublicationCard.cover }
  watchRequired:  { from: QbPublicationCard.watchRequired }
  videoDurationSec: { from: QbPublicationCard.videoDurationSec }
  video:          { from: QbPublicationCard.video }
  kind:           { from: QbPublicationCard.kind }
  language:       { from: QbPublicationCard.language }
  languages:      { from: QbPublicationCard.languages }
  displayLanguage: { kind: { type: text, maxLength: 2 } }
  referralDescription: { kind: { type: text, maxLength: 500 } }
  intro:          { kind: { type: text, maxLength: 5000 } }
  materials:      { kind: { type: text } }
  status:         { from: QbPublicationCard.status }
  startAt:        { from: QbPublicationCard.startAt }
  endAt:          { from: QbPublicationCard.endAt }
  timeLimitSec:   { from: QbPublicationCard.timeLimitSec }
  questionCount:  { from: QbPublicationCard.questionCount }
  pickCount:      { from: QbPublicationCard.pickCount }
  entryFee:       { from: QbPublicationCard.entryFee }
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
  fulfillerCompany: { from: QbSponsor.company }
  sponsorIntro:   { from: QbSponsor.intro }
  sponsorAvatar:  { from: QbSponsor.avatar }
  sponsorHandle:  { from: QbSponsorPage.handle }
  sponsorCountry: { from: QbPublicationCard.sponsorCountry }
  completions:    { from: QbPublicationCounter.completions }
  last1h:         { from: QbPublicationStats.last1h }
  last24h:        { from: QbPublicationStats.last24h }
  last7d:         { from: QbPublicationStats.last7d }
  last30d:        { from: QbPublicationStats.last30d }
  statsAt:        { from: QbStatsRun.refreshedAt }
  canStart:       { kind: { type: bool } }
  regionBlocked:  { kind: { type: bool } }
  matched:        { kind: { type: bool } }
  prerequisitesMet: { kind: { type: bool } }
  prerequisites:  { kind: { type: text } }
  heldUntil:      { from: QbCertificationRecord.validUntil }
  certification:  { from: QbCertificationRound.certification }
  certificationNames: { kind: { type: text } }
  referral:       { from: QbPublicationCard.referral }
  myReferralStatus:    { from: QbReferral.status }
  myReferralExpiresAt: { from: QbReferral.expiresAt }
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
-- and budget not used up, a sponsor who can pay), no attempt yet, not the caller's own publication, (Q47) matched: the caller's
-- profile meets the conditions (a shared link still shows the page, so the App can ask for the profile). The
-- introduction and the materials come from the version's snapshot, never its questions (design section 9). The
-- windows' counts and their time are the statistics job's (every ten minutes); the total is the counter's, live.
-- The counters' two sides (Q10a4: starts s, completions n) are read in this one statement, as in qb.app.feed.
SELECT
    d.publicationId AS publicationId,
    d.name AS name,
    d.quizTitle AS quizTitle,
    d.cover AS cover,
    d.watchRequired AS watchRequired,
    d.videoDurationSec AS videoDurationSec,
    d.video AS video,
    d.kind AS kind,
    d.language AS language,
    d.languages AS languages,
    d.displayLanguage AS displayLanguage,
    d.referralDescription AS referralDescription,
    d.intro AS intro,
    d.materials AS materials,
    d.status AS status,
    d.startAt AS startAt,
    d.endAt AS endAt,
    d.timeLimitSec AS timeLimitSec,
    d.questionCount AS questionCount,
    d.pickCount AS pickCount,
    d.entryFee AS entryFee,
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
    d.fulfillerCompany AS fulfillerCompany,
    d.sponsorIntro AS sponsorIntro,
    d.sponsorAvatar AS sponsorAvatar,
    d.sponsorHandle AS sponsorHandle,
    d.sponsorCountry AS sponsorCountry,
    d.completions AS completions,
    d.last1h AS last1h,
    d.last24h AS last24h,
    d.last7d AS last7d,
    d.last30d AS last30d,
    d.statsAt AS statsAt,
    (d.shown AND d.startable AND d.matched AND d.prerequisitesMet AND d.heldUntil IS NULL AND d.myAttemptId IS NULL
     AND NOT d.regionBlocked) AS canStart,
    d.regionBlocked AS regionBlocked,
    d.matched AS matched,
    d.prerequisitesMet AS prerequisitesMet,
    d.prerequisites AS prerequisites,
    d.heldUntil AS heldUntil,
    d.certification AS certification,
    d.certificationNames AS certificationNames,
    d.referral AS referral,
    d.myReferralStatus AS myReferralStatus,
    d.myReferralExpiresAt AS myReferralExpiresAt,
    d.myAttemptId AS myAttemptId,
    d.myStatus AS myStatus,
    d.myDeadline AS myDeadline
FROM (
SELECT
    c.{{QbPublicationCard.publicationId}}  AS publicationId,
    -- Q73b: the texts in the language shown (the card's translations; the version's for its introduction and
    -- materials, a language without a material of its own showing the base's, which says it is the base's), else
    -- the card's own and the version's base.
    coalesce(CAST(c.{{QbPublicationCard.translations}} AS jsonb) -> dl.lang ->> 'name',
             c.{{QbPublicationCard.name}}) AS name,
    coalesce(CAST(c.{{QbPublicationCard.translations}} AS jsonb) -> dl.lang ->> 'quizTitle',
             c.{{QbPublicationCard.quizTitle}}) AS quizTitle,
    -- Q98b: the cover of the language shown, else the card's.
    coalesce(CASE dl.lang WHEN 'ja' THEN c.{{QbPublicationCard.coverJa}} WHEN 'zh' THEN c.{{QbPublicationCard.coverZh}}
        WHEN 'en' THEN c.{{QbPublicationCard.coverEn}} END, c.{{QbPublicationCard.cover}}) AS cover,
    -- Q75: whether the card's video must be watched before starting (the App disables the start until then), and
    -- how long it is as the sponsor gave it.
    c.{{QbPublicationCard.watchRequired}}  AS watchRequired,
    c.{{QbPublicationCard.videoDurationSec}} AS videoDurationSec,
    -- F-S12: the card's video, which leads the start page when it is the shared video the publication picked (no
    -- material of the version): the page plays what the home's card played.
    c.{{QbPublicationCard.video}}          AS video,
    c.{{QbPublicationCard.kind}}           AS kind,
    c.{{QbPublicationCard.language}}       AS language,
    coalesce(c.{{QbPublicationCard.languages}}, c.{{QbPublicationCard.language}}) AS languages,
    dl.lang                                AS displayLanguage,
    CAST(c.{{QbPublicationCard.translations}} AS jsonb) -> dl.lang ->> 'referralDescription' AS referralDescription,
    coalesce(CAST(v.{{QbQuizVersion.content}} AS jsonb) -> 'translations' -> dl.lang -> 'texts' ->> 'intro',
             CAST(v.{{QbQuizVersion.content}} AS jsonb) ->> 'intro') AS intro,
    (SELECT CAST(coalesce(jsonb_agg(coalesce(
                (SELECT own || jsonb_build_object('language', dl.lang) || CASE WHEN bm ? 'watchToAnswer'
                     THEN jsonb_build_object('watchToAnswer', bm -> 'watchToAnswer') ELSE '{}'::jsonb END
                     -- Q75: the duration is the base's too.
                     || CASE WHEN bm ? 'durationSec' THEN jsonb_build_object('durationSec', bm -> 'durationSec')
                        ELSE '{}'::jsonb END
                 FROM jsonb_array_elements(CAST(v.{{QbQuizVersion.content}} AS jsonb) -> 'translations' -> dl.lang
                                           -> 'materials') own
                 WHERE own -> 'no' = bm -> 'no'), bm) ORDER BY CAST(bm ->> 'no' AS integer)), '[]'::jsonb) AS text)
     FROM jsonb_array_elements(CAST(v.{{QbQuizVersion.content}} AS jsonb) -> 'materials') bm) AS materials,
    c.{{QbPublicationCard.status}}         AS status,
    c.{{QbPublicationCard.startAt}}        AS startAt,
    c.{{QbPublicationCard.endAt}}          AS endAt,
    c.{{QbPublicationCard.timeLimitSec}}   AS timeLimitSec,
    c.{{QbPublicationCard.questionCount}}  AS questionCount,
    c.{{QbPublicationCard.pickCount}}      AS pickCount,
    c.{{QbPublicationCard.entryFee}}       AS entryFee,
    c.{{QbPublicationCard.fullScore}}      AS fullScore,
    c.{{QbPublicationCard.passScore}}      AS passScore,
    c.{{QbPublicationCard.maxReward}}      AS maxReward,
    c.{{QbPublicationCard.rewardMethod}}   AS rewardMethod,
    coalesce(CAST(c.{{QbPublicationCard.translations}} AS jsonb) -> dl.lang ->> 'redeemDescription',
             c.{{QbPublicationCard.redeemDescription}}) AS redeemDescription,
    c.{{QbPublicationCard.digitalDelivery}} AS digitalDelivery,
    coalesce(CAST(c.{{QbPublicationCard.translations}} AS jsonb) -> dl.lang ->> 'digitalDescription',
             c.{{QbPublicationCard.digitalDescription}}) AS digitalDescription,
    c.{{QbPublicationCard.audience}}       AS audience,
    (inv.{{QbInvitation.invitationId}} IS NOT NULL) AS invited,
    sp.{{QbSponsor.contactName}}           AS sponsorName,
    sp.{{QbSponsor.company}}               AS sponsorCompany,
    -- Q89b-1: another sponsor's benefit is collected at its provider's place.
    (SELECT fs.{{QbSponsor.company}} FROM {{QbSponsor}} fs
     WHERE fs.{{QbSponsor.userId}} = c.{{QbPublicationCard.fulfillerId}}) AS fulfillerCompany,
    -- Q73c: the sponsor's introduction in the page's language when translated.
    coalesce(CAST(sp.{{QbSponsor.introTranslations}} AS jsonb) -> dl.lang -> 'intro' ->> 'text',
             sp.{{QbSponsor.intro}})       AS sponsorIntro,
    sp.{{QbSponsor.avatar}}                AS sponsorAvatar,
    -- Q60a: the sponsor's published page, which the sponsor's name links to; none while it is not published.
    sp_page.{{QbSponsorPage.handle}}       AS sponsorHandle,
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
    -- Q47: an invited publication takes its list; a public one the caller's profile meeting its conditions.
    (c.{{QbPublicationCard.audience}} = 'INVITED' OR NOT EXISTS (
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
                            BETWEEN pc.{{QbPublicationCriterion.minValue}} AND pc.{{QbPublicationCriterion.maxValue}})))))) AS matched,
    -- Q76 (D-Q76-2): the caller has finished an attempt (in progress does not count) of every publication this one
    -- requires, a pass where it asks for one (play.PrerequisiteCheck, the one definition; qb.app.feed and
    -- qb.system.audience-count write the same; the view holds the caller's own attempts only).
    NOT EXISTS (
         SELECT 1 FROM {{QbPublicationPrerequisite}} pr
         WHERE pr.{{QbPublicationPrerequisite.publicationId}} = c.{{QbPublicationCard.publicationId}}
           -- Q86 (D-Q86-5): or a certification the caller must hold now.
           AND NOT ((pr.{{QbPublicationPrerequisite.requiredPublicationId}} IS NOT NULL AND EXISTS (
                 SELECT 1 FROM {{QbAttempt}} ra
                 WHERE ra.{{QbAttempt.publicationId}} = pr.{{QbPublicationPrerequisite.requiredPublicationId}}
                   AND ra.{{QbAttempt.status}} <> 'IN_PROGRESS'
                   AND (pr.{{QbPublicationPrerequisite.requirement}} = 'ANSWERED' OR ra.{{QbAttempt.passed}})))
            OR (pr.{{QbPublicationPrerequisite.certification}} IS NOT NULL AND EXISTS (
                   SELECT 1
                   FROM (SELECT * FROM (VALUES ('SPONSOR_APPLY', 1, 31536000), ('REFERRER', 1, 31536000), ('TERMS_APP_UNDERSTOOD', 1, 31536000), ('CONTEST_RULES_UNDERSTOOD', 1, 31536000), ('TERMS_SPONSOR_UNDERSTOOD', 1, 31536000), ('BENEFIT_PROVIDER', 1, 31536000), ('BENEFIT_SPONSOR', 1, 31536000)) AS v(cert, ver, secs) UNION ALL SELECT 'sp:' || sc.{{QbSponsorCertification.certificationId}}, sc.{{QbSponsorCertification.certificationVersion}}, sc.{{QbSponsorCertification.validityDays}} * 86400 FROM {{QbSponsorCertification}} sc) AS cat(cert, ver, secs)
                   CROSS JOIN LATERAL (
                       SELECT rec.{{QbCertificationRecord.certificationVersion}} AS ver, rec.{{QbCertificationRecord.at}} AS at,
                              rec.{{QbCertificationRecord.validUntil}} AS until
                       FROM {{QbCertificationRecord}} rec
                       WHERE rec.{{QbCertificationRecord.certification}} = cat.cert
                         AND rec.{{QbCertificationRecord.kind}} IN ('PASSED', 'GRANTED')
                       UNION ALL
                       SELECT r2.{{QbCertificationRound.certificationVersion}}, a.{{QbAttempt.submittedAt}},
                              a.{{QbAttempt.submittedAt}} + make_interval(secs => cat.secs)
                       FROM {{QbCertificationRound}} r2
                       JOIN {{QbAttempt}} a ON CAST(a.{{QbAttempt.publicationId}} AS text) = r2.{{QbCertificationRound.publicationId}}
                       WHERE r2.{{QbCertificationRound.certification}} = cat.cert
                         AND a.{{QbAttempt.passed}} AND a.{{QbAttempt.submittedAt}} IS NOT NULL
                         AND (r2.{{QbCertificationRound.closedAt}} IS NULL
                              OR a.{{QbAttempt.submittedAt}} < r2.{{QbCertificationRound.closedAt}})) e
                   WHERE cat.cert = pr.{{QbPublicationPrerequisite.certification}}
                     AND e.ver = cat.ver AND e.until > CAST(:at AS timestamptz)
                     AND e.at > coalesce((SELECT max(rv.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} rv
                                          WHERE rv.{{QbCertificationRecord.certification}} = cat.cert
                                            AND rv.{{QbCertificationRecord.kind}} = 'REVOKED'), '-infinity'))))) AS prerequisitesMet,
    -- Q76 (D-Q76-3): what to do first, each with its name in the page's language (the required publication's card,
    -- when it has one: a required publication was submitted, so it has one once live), whether the caller met it, and
    -- whether it still accepts attempts (its page says why not otherwise).
    (SELECT CAST(coalesce(jsonb_agg(jsonb_build_object(
                'requiredPublicationId', pr.{{QbPublicationPrerequisite.requiredPublicationId}},
                'certification', pr.{{QbPublicationPrerequisite.certification}},
                -- Q88a: a sponsor's own certification is named by its row (the catalog's by the front ends' texts).
                'certificationNames', psc.{{QbSponsorCertification.name}},
                'requirement', pr.{{QbPublicationPrerequisite.requirement}},
                'name', coalesce(CAST(rc.{{QbPublicationCard.translations}} AS jsonb) -> dl.lang ->> 'name',
                                 rc.{{QbPublicationCard.name}}),
                'live', rc.{{QbPublicationCard.status}} = 'LIVE' AND rc.{{QbPublicationCard.startAt}} <= :at
                        AND rc.{{QbPublicationCard.endAt}} > :at,
                -- Q86: a certification's current round (open, its publication live), to send the taker to.
                'roundPublicationId', (SELECT max(cr2.{{QbCertificationRound.publicationId}})
                                       FROM {{QbCertificationRound}} cr2
                                       JOIN {{QbPublicationCard}} rp ON CAST(rp.{{QbPublicationCard.publicationId}} AS text)
                                           = cr2.{{QbCertificationRound.publicationId}}
                                       WHERE cr2.{{QbCertificationRound.certification}} = pr.{{QbPublicationPrerequisite.certification}}
                                         AND cr2.{{QbCertificationRound.closedAt}} IS NULL
                                         AND rp.{{QbPublicationCard.status}} = 'LIVE' AND rp.{{QbPublicationCard.startAt}} <= :at
                                         AND rp.{{QbPublicationCard.endAt}} > :at),
                'met', ((pr.{{QbPublicationPrerequisite.requiredPublicationId}} IS NOT NULL AND EXISTS (
                      SELECT 1 FROM {{QbAttempt}} ra
                      WHERE ra.{{QbAttempt.publicationId}} = pr.{{QbPublicationPrerequisite.requiredPublicationId}}
                        AND ra.{{QbAttempt.status}} <> 'IN_PROGRESS'
                        AND (pr.{{QbPublicationPrerequisite.requirement}} = 'ANSWERED' OR ra.{{QbAttempt.passed}})))
                 OR (pr.{{QbPublicationPrerequisite.certification}} IS NOT NULL AND EXISTS (
                        SELECT 1
                        FROM (SELECT * FROM (VALUES ('SPONSOR_APPLY', 1, 31536000), ('REFERRER', 1, 31536000), ('TERMS_APP_UNDERSTOOD', 1, 31536000), ('CONTEST_RULES_UNDERSTOOD', 1, 31536000), ('TERMS_SPONSOR_UNDERSTOOD', 1, 31536000), ('BENEFIT_PROVIDER', 1, 31536000), ('BENEFIT_SPONSOR', 1, 31536000)) AS v(cert, ver, secs) UNION ALL SELECT 'sp:' || sc.{{QbSponsorCertification.certificationId}}, sc.{{QbSponsorCertification.certificationVersion}}, sc.{{QbSponsorCertification.validityDays}} * 86400 FROM {{QbSponsorCertification}} sc) AS cat(cert, ver, secs)
                        CROSS JOIN LATERAL (
                            SELECT rec.{{QbCertificationRecord.certificationVersion}} AS ver, rec.{{QbCertificationRecord.at}} AS at,
                                   rec.{{QbCertificationRecord.validUntil}} AS until
                            FROM {{QbCertificationRecord}} rec
                            WHERE rec.{{QbCertificationRecord.certification}} = cat.cert
                              AND rec.{{QbCertificationRecord.kind}} IN ('PASSED', 'GRANTED')
                            UNION ALL
                            SELECT r2.{{QbCertificationRound.certificationVersion}}, a.{{QbAttempt.submittedAt}},
                                   a.{{QbAttempt.submittedAt}} + make_interval(secs => cat.secs)
                            FROM {{QbCertificationRound}} r2
                            JOIN {{QbAttempt}} a ON CAST(a.{{QbAttempt.publicationId}} AS text) = r2.{{QbCertificationRound.publicationId}}
                            WHERE r2.{{QbCertificationRound.certification}} = cat.cert
                              AND a.{{QbAttempt.passed}} AND a.{{QbAttempt.submittedAt}} IS NOT NULL
                              AND (r2.{{QbCertificationRound.closedAt}} IS NULL
                                   OR a.{{QbAttempt.submittedAt}} < r2.{{QbCertificationRound.closedAt}})) e
                        WHERE cat.cert = pr.{{QbPublicationPrerequisite.certification}}
                          AND e.ver = cat.ver AND e.until > CAST(:at AS timestamptz)
                          AND e.at > coalesce((SELECT max(rv.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} rv
                                               WHERE rv.{{QbCertificationRecord.certification}} = cat.cert
                                                 AND rv.{{QbCertificationRecord.kind}} = 'REVOKED'), '-infinity')))))
                ORDER BY rc.{{QbPublicationCard.name}}, pr.{{QbPublicationPrerequisite.requiredPublicationId}}), '[]') AS text)
         FROM {{QbPublicationPrerequisite}} pr
         LEFT JOIN {{QbSponsorCertification}} psc
             ON 'sp:' || psc.{{QbSponsorCertification.certificationId}} = pr.{{QbPublicationPrerequisite.certification}}
         LEFT JOIN {{QbPublicationCard}} rc
             ON rc.{{QbPublicationCard.publicationId}} = pr.{{QbPublicationPrerequisite.requiredPublicationId}}
         WHERE pr.{{QbPublicationPrerequisite.publicationId}} = c.{{QbPublicationCard.publicationId}}) AS prerequisites,
    -- Q70a (D-Q70-1, option B): until when the caller holds the certification of this round (qb.app.feed, the same).
    (SELECT max(e.until)
         FROM {{QbCertificationRound}} cr
         JOIN (SELECT * FROM (VALUES ('SPONSOR_APPLY', 1, 31536000), ('REFERRER', 1, 31536000), ('TERMS_APP_UNDERSTOOD', 1, 31536000), ('CONTEST_RULES_UNDERSTOOD', 1, 31536000), ('TERMS_SPONSOR_UNDERSTOOD', 1, 31536000), ('BENEFIT_PROVIDER', 1, 31536000), ('BENEFIT_SPONSOR', 1, 31536000)) AS v(cert, ver, secs) UNION ALL SELECT 'sp:' || sc.{{QbSponsorCertification.certificationId}}, sc.{{QbSponsorCertification.certificationVersion}}, sc.{{QbSponsorCertification.validityDays}} * 86400 FROM {{QbSponsorCertification}} sc) AS cat(cert, ver, secs)
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
                                  AND rv.{{QbCertificationRecord.kind}} = 'REVOKED'), '-infinity')) AS heldUntil,
    -- Q88a (D-Q88-5): the certification this is a round of, and its name when it is a sponsor's.
    cr0.{{QbCertificationRound.certification}} AS certification,
    CAST(sc0.{{QbSponsorCertification.name}} AS text) AS certificationNames,
    -- Q70: "referrals welcome" as frozen on the card, and the caller's own referral to it, if a friend referred them
    -- (the view holds the caller's as referee only; the places left come with the referrer's link).
    c.{{QbPublicationCard.referral}}       AS referral,
    mr.{{QbReferral.status}}               AS myReferralStatus,
    mr.{{QbReferral.expiresAt}}            AS myReferralExpiresAt,
    m.{{QbAttempt.attemptId}}              AS myAttemptId,
    m.{{QbAttempt.status}}                 AS myStatus,
    m.{{QbAttempt.deadline}}               AS myDeadline,
    -- F-S11: from mainland China (the page passes what the platform's clientRegion() said; the start checks the address
    -- itself), a publication whose card has a foreign video, or none recorded yet, is not for the caller unless an exception
    -- of headquarters is in force (the public dataset holds those not revoked; region.RegionRules is the one definition).
    (CAST(:mainlandChina AS boolean) IS TRUE AND coalesce(c.{{QbPublicationCard.foreignVideo}}, true)
         AND NOT EXISTS (
             SELECT 1 FROM {{QbRegionException}} rx
             WHERE (rx.{{QbRegionException.expiresOn}} IS NULL
                    OR rx.{{QbRegionException.expiresOn}} >= CAST(CAST(:at AS timestamptz) AT TIME ZONE 'UTC' AS date))
               AND ((rx.{{QbRegionException.kind}} = 'PUBLICATION'
                     AND rx.{{QbRegionException.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text))
                    OR (rx.{{QbRegionException.kind}} = 'SPONSOR'
                        AND rx.{{QbRegionException.sponsorId}} = c.{{QbPublicationCard.publisherId}})))) AS regionBlocked
FROM {{QbPublicationCard}} c
JOIN {{QbPublicationCounter}} n ON n.{{QbPublicationCounter.publicationId}} = c.{{QbPublicationCard.publicationId}}
JOIN {{QbPublicationStartCounter}} s
    ON s.{{QbPublicationStartCounter.publicationId}} = c.{{QbPublicationCard.publicationId}}
JOIN {{QbQuizVersion}} v ON v.{{QbQuizVersion.versionId}} = c.{{QbPublicationCard.versionId}}
LEFT JOIN {{QbPublicationStats}} st ON st.{{QbPublicationStats.publicationId}} = c.{{QbPublicationCard.publicationId}}
LEFT JOIN {{QbStatsRun}} sr ON sr.{{QbStatsRun.job}} = 'qb.publication-stats'
LEFT JOIN {{QbWallet}} w ON w.{{QbWallet.ownerId}} = c.{{QbPublicationCard.publisherId}}
    AND w.{{QbWallet.kind}} = 'SPONSOR'
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = c.{{QbPublicationCard.publisherId}}
    AND sp.{{QbSponsor.reviewStatus}} = 'APPROVED'
LEFT JOIN {{QbSponsorPage}} sp_page ON sp_page.{{QbSponsorPage.userId}} = sp.{{QbSponsor.userId}}
LEFT JOIN {{QbAttempt}} m ON m.{{QbAttempt.publicationId}} = c.{{QbPublicationCard.publicationId}}
-- Q88a: the round the card is, if any, and the name of a sponsor's certification (the catalog's names are the
-- front ends' texts).
LEFT JOIN {{QbCertificationRound}} cr0
    ON cr0.{{QbCertificationRound.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
LEFT JOIN {{QbSponsorCertification}} sc0
    ON 'sp:' || sc0.{{QbSponsorCertification.certificationId}} = cr0.{{QbCertificationRound.certification}}
-- Q73b: the language shown: the caller's attempt's, else the first of the asked ones the card offers, else its main.
CROSS JOIN LATERAL (
    SELECT coalesce(m.{{QbAttempt.language}},
        (SELECT u.l FROM unnest(string_to_array(CAST(:languages AS text), ',')) WITH ORDINALITY AS u(l, i)
         WHERE u.l = ANY(string_to_array(coalesce(c.{{QbPublicationCard.languages}},
                                                  c.{{QbPublicationCard.language}}), ','))
         ORDER BY u.i LIMIT 1), c.{{QbPublicationCard.language}}) AS lang) dl
LEFT JOIN {{QbReferral}} mr ON mr.{{QbReferral.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
LEFT JOIN {{QbPublicationOwner}} own
    ON own.{{QbPublicationOwner.publicationId}} = c.{{QbPublicationCard.publicationId}}
LEFT JOIN {{QbInvitation}} inv ON inv.{{QbInvitation.publicationId}} = c.{{QbPublicationCard.publicationId}}
WHERE c.{{QbPublicationCard.publicationId}} = :publicationId
  -- S0 (D-S0-4): a shop's publication (S2) is not a page of the App.
  AND c.{{QbPublicationCard.publisherKind}} = 'SPONSOR'
) d
WHERE d.shown OR d.myAttemptId IS NOT NULL
