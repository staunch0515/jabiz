/*---
id: qb.app.feed
description: The publications a taker can answer now (U-10 – U-14), newest first; searched by name and title
entities: [SysParam, QbRegionException, QbUserSettings, QbTopic, QbBlock, QbExposure, QbPublicationCard, QbPublicationCounter, QbPublicationStartCounter, QbWallet, QbCountry, QbSponsor, QbSponsorPage, QbPublicationOwner, QbAttempt, QbProfileAttribute, QbPublicationCriterion, QbTreeNode, QbTreeLit, QbTreeLitRevocation]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
  QbRegionException: urn:jabiz:dataset:public:QbRegionException
  QbUserSettings: urn:jabiz:dataset:me:QbUserSettings
  QbTopic: urn:jabiz:dataset:default:QbTopic
  QbBlock: urn:jabiz:dataset:me:QbBlock
  QbExposure: urn:jabiz:dataset:me:QbExposure
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
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeLit: urn:jabiz:dataset:me:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:me:QbTreeLitRevocation
params:
  at:      { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the feed is for (the client's now) }
  country: { kind: { type: text, maxLength: 2 }, description: "The taker's country (QbCountry.code); without one only publications aimed at GLOBAL" }
  q:       { kind: { type: text, maxLength: 100 }, description: "Only publications whose name, quiz title, a topic's name (any language) or a custom word contains this text" }
  includeAnswered: { kind: { type: bool }, description: "If true, also the publications the caller has answered (Q34); by default those are left out" }
  method:  { like: QbPublicationCard.rewardMethod, description: "Only publications whose reward comes this way (Q22, Q33): PLATFORM, SPONSOR_CASH, OFFLINE_REDEEM or DIGITAL_PRIZE" }
  mainlandChina: { kind: { type: bool }, description: "F-S11: whether the caller's address is in mainland China, as GET /api/client/region said; publications with a foreign video and no exception are then not for them" }
  shorts:  { kind: { type: bool }, description: "F-S10: if true, only the publications whose video is vertical (QbPublicationCard.videoVertical), for the home's shelf of shorts and its full-screen vertical mode; the same conditions as the feed otherwise" }
  push:    { kind: { type: bool }, description: "F-S13: if true, only the pushes of the vertical mode: publications whose vertical video is another's shared video (its provider is not its publisher) that the caller never opened; the same conditions as the feed otherwise (blocks, strict mode, region)" }
  tree:    { kind: { type: text, maxLength: 36 }, description: "F-S3: only publications bound to a node of this knowledge graph (QbPublicationCard.treeId): what the caller can start now to light its nodes, under every other condition of the feed" }
  languages: { kind: { type: text, maxLength: 20 }, description: "Only publications offering one of these languages (Q59a, Q73b), comma-separated in the taker's order (ja, zh, en): their switches, or their interface language; each shown in the first of them it offers; all when not given" }
results:
  publicationId:  { from: QbPublicationCard.publicationId }
  name:           { from: QbPublicationCard.name }
  quizTitle:      { from: QbPublicationCard.quizTitle }
  cover:          { from: QbPublicationCard.cover }
  video:          { from: QbPublicationCard.video }
  watchRequired:  { from: QbPublicationCard.watchRequired }
  videoDurationSec: { from: QbPublicationCard.videoDurationSec }
  videoVertical:  { from: QbPublicationCard.videoVertical }
  quizId:         { from: QbPublicationCard.quizId }
  videoProviderId: { from: QbPublicationCard.videoProviderId }
  videoProviderName: { from: QbSponsor.company }
  exposed:        { kind: { type: bool } }
  pushTurn:       { kind: { type: numeric, precision: 9, scale: 0 } }
  pushRank:       { kind: { type: numeric, precision: 9, scale: 0 } }
  shortsPositions: { kind: { type: text, maxLength: 200 } }
  shortsPerShelf: { kind: { type: text, maxLength: 20 } }
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
  pickCount:      { from: QbPublicationCard.pickCount }
  entryFee:       { from: QbPublicationCard.entryFee }
  fullScore:      { from: QbPublicationCard.fullScore }
  treeId:         { from: QbPublicationCard.treeId }
  treeNodeId:     { from: QbPublicationCard.treeNodeId }
  myAttemptId:    { from: QbAttempt.attemptId }
  myStatus:       { from: QbAttempt.status }
  myDeadline:     { from: QbAttempt.deadline }
list:
  filters: [sponsorCountry, sponsorName, sponsorCompany, maxReward, rewardMethod, completions, startAt]
  sorts:   [startAt, maxReward, completions, pushTurn, pushRank]
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
    -- Q98b: the cover of the language shown, else the card's.
    coalesce(CASE dl.lang WHEN 'ja' THEN c.{{QbPublicationCard.coverJa}} WHEN 'zh' THEN c.{{QbPublicationCard.coverZh}}
        WHEN 'en' THEN c.{{QbPublicationCard.coverEn}} END, c.{{QbPublicationCard.cover}}) AS cover,
    c.{{QbPublicationCard.video}}          AS video,
    -- Q75: whether that video must be watched before starting, and how long it is.
    c.{{QbPublicationCard.watchRequired}}  AS watchRequired,
    c.{{QbPublicationCard.videoDurationSec}} AS videoDurationSec,
    -- F-S3: the knowledge graph's node the publication is bound to (the graph page links "can answer" to it).
    c.{{QbPublicationCard.treeId}}         AS treeId,
    c.{{QbPublicationCard.treeNodeId}}     AS treeNodeId,
    -- F-S10: whether that video is vertical, and where the home puts its shelves of shorts and how many each shows
    -- (the uncontrolled parameters qb.feed.shorts.positions and .per-shelf; none before the setup made them: the App
    -- then takes the setup's values).
    c.{{QbPublicationCard.videoVertical}}  AS videoVertical,
    -- F-S8: what of the card a taker can block besides the publication and its sponsor: its quiz and its shared video's
    -- provider (none without a shared video), with the provider's company to name it.
    c.{{QbPublicationCard.quizId}}         AS quizId,
    c.{{QbPublicationCard.videoProviderId}} AS videoProviderId,
    vp.{{QbSponsor.company}}               AS videoProviderName,
    -- F-S13: whether its video is another's shared video (the provider is not the publisher): its exposures count, in
    -- the vertical mode when vertical, on the start page otherwise (exposure.ExposureRules.othersVideo).
    (c.{{QbPublicationCard.sharedVideoId}} IS NOT NULL AND c.{{QbPublicationCard.videoProviderId}} IS NOT NULL
     AND c.{{QbPublicationCard.videoProviderId}} <> c.{{QbPublicationCard.publisherId}}) AS exposed,
    -- F-S13: the pushes take turns among their publishers (by id, which never leaves the server): the publisher's n-th
    -- push (newest first), and the publisher's place in a turn, an order of the publishers that changes every day (a
    -- hash of the id and the day: who comes first rotates); sorting by the two gives every publisher's first, then every
    -- publisher's second... The App gets the two numbers only. Only with push (none otherwise).
    CASE WHEN CAST(:push AS boolean) IS TRUE
         THEN row_number() OVER (PARTITION BY c.{{QbPublicationCard.publisherId}}
                                 ORDER BY c.{{QbPublicationCard.startAt}} DESC, c.{{QbPublicationCard.publicationId}}) END
        AS pushTurn,
    CASE WHEN CAST(:push AS boolean) IS TRUE
         THEN dense_rank() OVER (ORDER BY md5(c.{{QbPublicationCard.publisherId}}
                                       || CAST(CAST(CAST(:at AS timestamptz) AT TIME ZONE 'UTC' AS date) AS text)),
                                 c.{{QbPublicationCard.publisherId}}) END AS pushRank,
    CAST(shp.positions AS varchar(200))    AS shortsPositions,
    CAST(shp.perShelf AS varchar(20))      AS shortsPerShelf,
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
    c.{{QbPublicationCard.pickCount}}      AS pickCount,
    c.{{QbPublicationCard.entryFee}}       AS entryFee,
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
CROSS JOIN (
    SELECT max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.feed.shorts.positions' THEN CAST(p.{{SysParam.value}} AS text) END)
               AS positions,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.feed.shorts.per-shelf' THEN CAST(p.{{SysParam.value}} AS text) END)
               AS perShelf
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.feed.shorts.positions', 'qb.feed.shorts.per-shelf')) shp
JOIN {{QbPublicationCounter}} n ON n.{{QbPublicationCounter.publicationId}} = c.{{QbPublicationCard.publicationId}}
JOIN {{QbPublicationStartCounter}} s
    ON s.{{QbPublicationStartCounter.publicationId}} = c.{{QbPublicationCard.publicationId}}
LEFT JOIN {{QbWallet}} w ON w.{{QbWallet.ownerId}} = c.{{QbPublicationCard.publisherId}}
    AND w.{{QbWallet.kind}} = 'SPONSOR'
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = c.{{QbPublicationCard.publisherId}}
    AND sp.{{QbSponsor.reviewStatus}} = 'APPROVED'
LEFT JOIN {{QbSponsorPage}} sp_page ON sp_page.{{QbSponsorPage.userId}} = sp.{{QbSponsor.userId}}
LEFT JOIN {{QbSponsor}} vp ON vp.{{QbSponsor.userId}} = c.{{QbPublicationCard.videoProviderId}}
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
  -- F-S10: the shelf of shorts asks for the vertical ones only, under every other condition of the feed.
  AND (CAST(:shorts AS boolean) IS NOT TRUE OR c.{{QbPublicationCard.videoVertical}})
  -- F-S13: the vertical mode's pushes: another's vertical shared video, never opened by the caller (the view holds their
  -- own rows only), under every other condition of the feed (exposure.ExposureRules.pushable is the one definition;
  -- BlockIT and ExposureIT compare).
  AND (CAST(:push AS boolean) IS NOT TRUE
       OR (c.{{QbPublicationCard.videoVertical}}
           AND c.{{QbPublicationCard.sharedVideoId}} IS NOT NULL AND c.{{QbPublicationCard.videoProviderId}} IS NOT NULL
           AND c.{{QbPublicationCard.videoProviderId}} <> c.{{QbPublicationCard.publisherId}}
           AND NOT EXISTS (SELECT 1 FROM {{QbExposure}} ex
                           WHERE ex.{{QbExposure.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
                             AND ex.{{QbExposure.openedAt}} IS NOT NULL)))
  AND (CAST(:method AS text) IS NULL OR c.{{QbPublicationCard.rewardMethod}} = CAST(:method AS text))
  -- F-S3: the graph page asks for the publications bound to one graph's nodes, under every other condition of the feed
  -- (tree.TreeNodeStates: a node with a row here is CAN_ANSWER for the caller).
  AND (CAST(:tree AS text) IS NULL OR c.{{QbPublicationCard.treeId}} = CAST(:tree AS text))
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
  -- F-S2b1: a publication bound to a knowledge graph's node is for whoever has lit, now, every node it wants lit before
  -- answering, while the node is in effect (tree.TreeLights and play.TreeGate are the definition; TreeLightsIT compares).
  AND (c.{{QbPublicationCard.treeNodeId}} IS NULL OR EXISTS (
      SELECT 1 FROM {{QbTreeNode}} tn
      WHERE tn.{{QbTreeNode.treeId}} = c.{{QbPublicationCard.treeId}} AND tn.{{QbTreeNode.nodeId}} = c.{{QbPublicationCard.treeNodeId}}
        AND tn.{{QbTreeNode.active}}
        AND NOT EXISTS (
            SELECT 1 FROM jsonb_array_elements_text(CAST(coalesce(tn.{{QbTreeNode.beforeAnswer}}, '[]') AS jsonb)) AS ba(node)
            WHERE NOT EXISTS (
                SELECT 1 FROM {{QbTreeLit}} tl
                WHERE tl.{{QbTreeLit.treeId}} = split_part(ba.node, '/', 1) AND tl.{{QbTreeLit.nodeId}} = split_part(ba.node, '/', 2)
                  AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} tr
                                  WHERE tr.{{QbTreeLitRevocation.litId}} = CAST(tl.{{QbTreeLit.litId}} AS text))))))
  -- F-S11: from mainland China (the page passes what the platform's clientRegion() said; the start checks the address
  -- itself), a publication whose card has a foreign video, or none recorded yet, is not for the caller unless an exception
  -- of headquarters is in force (the public dataset holds those not revoked; region.RegionRules is the one definition).
  AND NOT (CAST(:mainlandChina AS boolean) IS TRUE AND coalesce(c.{{QbPublicationCard.foreignVideo}}, true)
         AND NOT EXISTS (
             SELECT 1 FROM {{QbRegionException}} rx
             WHERE (rx.{{QbRegionException.expiresOn}} IS NULL
                    OR rx.{{QbRegionException.expiresOn}} >= CAST(CAST(:at AS timestamptz) AT TIME ZONE 'UTC' AS date))
               AND ((rx.{{QbRegionException.kind}} = 'PUBLICATION'
                     AND rx.{{QbRegionException.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text))
                    OR (rx.{{QbRegionException.kind}} = 'SPONSOR'
                        AND rx.{{QbRegionException.sponsorId}} = c.{{QbPublicationCard.publisherId}}))))
  -- F-S9: with the caller's strict mode on (their own settings: the view holds their row only), only a publication
  -- sharing a word of the word list with the topics they like; each word counts as the one it was merged into, a
  -- custom word never (its own column), a card without words never. topic.TopicRules.strictAllows is the one
  -- definition (TopicIT compares the two); F-S13's push (the parameter push) is this template, so it applies it too.
  AND (NOT coalesce((SELECT us.{{QbUserSettings.strictTopics}} FROM {{QbUserSettings}} us), false)
       OR EXISTS (
           SELECT 1
           FROM unnest(string_to_array(c.{{QbPublicationCard.topics}}, ',')) AS ct(code)
           JOIN {{QbTopic}} t ON t.{{QbTopic.topicId}} = ct.code
           JOIN (SELECT coalesce(lt.{{QbTopic.mergedInto}}, lt.{{QbTopic.topicId}}) AS code
                 FROM {{QbUserSettings}} lus
                 CROSS JOIN unnest(string_to_array(lus.{{QbUserSettings.likedTopics}}, ',')) AS l(code)
                 JOIN {{QbTopic}} lt ON lt.{{QbTopic.topicId}} = l.code) liked
               ON liked.code = coalesce(t.{{QbTopic.mergedInto}}, t.{{QbTopic.topicId}})))
  -- F-S8: not blocked by the caller (the view holds their own blocks only): neither the publication, nor the quiz its card
  -- froze (its later publications too), nor its sponsor, nor its shared video's provider. A hard block: nothing the
  -- card pays or promotes gets past it. block.BlockRules.blocks is the one definition; qb.app.publication,
  -- qb.app.inbox, qb.app.inbox-unread and the start (block.BlockStart) write the same (BlockIT compares them); F-S6's
  -- search applies it too, and F-S13's push (the parameter push) is this template.
  AND NOT EXISTS (
      SELECT 1 FROM {{QbBlock}} bl
      WHERE ((bl.{{QbBlock.kind}} = 'PUBLICATION'
              AND bl.{{QbBlock.objectId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text))
          OR (bl.{{QbBlock.kind}} = 'QUIZ' AND bl.{{QbBlock.objectId}} = c.{{QbPublicationCard.quizId}})
          OR (bl.{{QbBlock.kind}} = 'SPONSOR' AND bl.{{QbBlock.objectId}} = c.{{QbPublicationCard.publisherId}})
          OR (bl.{{QbBlock.kind}} = 'PROVIDER'
              AND bl.{{QbBlock.objectId}} = c.{{QbPublicationCard.videoProviderId}})))
  AND NOT EXISTS (SELECT 1 FROM {{QbPublicationOwner}} own

                  WHERE own.{{QbPublicationOwner.publicationId}} = c.{{QbPublicationCard.publicationId}})
  AND (CAST(:q AS text) IS NULL
       OR strpos(lower(c.{{QbPublicationCard.name}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(c.{{QbPublicationCard.quizTitle}}), lower(CAST(:q AS text))) > 0
       OR EXISTS (SELECT 1
                  FROM unnest(string_to_array(c.{{QbPublicationCard.topics}}, ',')) AS qt(code)
                  JOIN {{QbTopic}} qw ON qw.{{QbTopic.topicId}} = qt.code
                  CROSS JOIN jsonb_each_text(CAST(qw.{{QbTopic.name}} AS jsonb)) AS qn(language, name)
                  WHERE strpos(lower(qn.name), lower(CAST(:q AS text))) > 0)
       OR EXISTS (SELECT 1
                  FROM jsonb_array_elements_text(CAST(coalesce(c.{{QbPublicationCard.customTopics}}, '[]') AS jsonb))
                       AS qc(word)
                  WHERE strpos(lower(qc.word), lower(CAST(:q AS text))) > 0))
