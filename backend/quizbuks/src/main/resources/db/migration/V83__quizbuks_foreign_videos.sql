-- F-S11 (docs/quizbuks/plans/impl/F-S11.md): publications with a video mainland China cannot play are not answered
-- from a mainland address (by the platform's ClientCountry, decision D74), unless headquarters allowed it.

-- The card freezes whether its version has such a video (content.ForeignVideos), when it goes on and at a switch of
-- version. None counts as foreign: a card the backfill below did not reach is refused rather than shown.
ALTER TABLE qb_publication_card ADD COLUMN foreign_video boolean;

-- Whether a link is one video of a site of the setup's first value of qb.video.mainland-accessible-sites (Bilibili,
-- Youku, Tencent Video), read as VideoEmbeds reads it: blanks around it dropped, the scheme and the host in any case,
-- the path as written. Only for the backfill below (the application decides by VideoEmbeds and the parameter);
-- ForeignVideoIT compares it with VideoEmbeds over the cases of VideoEmbedsTest.
CREATE FUNCTION quizbuks_mainland_video_link(link text) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
    SELECT coalesce(
        -- Bilibili: bilibili.com/video/<BV id>[/], and its player with ?bvid=<BV id>.
        (u ~* '^https?://((www|m)\.)?bilibili\.com/'
            AND u ~ '^[^/]*//[^/]*/video/BV[0-9A-Za-z]{10}/?([?#][^[:space:]]*)?$')
        OR (u ~* '^https?://player\.bilibili\.com/player\.html\?'
            AND u ~ '[?&]bvid=BV[0-9A-Za-z]{10}(&[^[:space:]]*|#[^[:space:]]*)?$')
        -- Youku: v.youku.com/v_show/id_<id>.html, player.youku.com/embed/<id>.
        OR (u ~* '^https?://v\.youku\.com/'
            AND u ~ '^[^/]*//[^/]*/v_show/id_[A-Za-z0-9]{8,28}={0,2}\.html([?#][^[:space:]]*)?$')
        OR (u ~* '^https?://player\.youku\.com/'
            AND u ~ '^[^/]*//[^/]*/embed/[A-Za-z0-9]{8,28}={0,2}([?#][^[:space:]]*)?$')
        -- Tencent Video: v.qq.com/x/page/<vid>.html, /x/cover/<cover>/<vid>.html, the player with ?vid=<vid>.
        OR (u ~* '^https?://v\.qq\.com/'
            AND (u ~ '^[^/]*//[^/]*/x/page/[a-z0-9]{11}\.html([?#][^[:space:]]*)?$'
                OR u ~ '^[^/]*//[^/]*/x/cover/[a-z0-9]{15}/[a-z0-9]{11}\.html([?#][^[:space:]]*)?$'
                OR (u ~ '^[^/]*//[^/]*/txp/iframe/player\.html\?'
                    AND u ~ '[?&]vid=[a-z0-9]{11}(&[^[:space:]]*|#[^[:space:]]*)?$'))), false)
    FROM (SELECT btrim(link) AS u) l
$$;

-- The backfill, from each card's version: foreign when any video link (the base's materials and each other
-- language's own, Q73) is not blank and not such a link.
UPDATE qb_publication_card c
SET foreign_video = EXISTS (
    SELECT 1
    FROM qb_quiz_version_version v
    CROSS JOIN LATERAL (
        SELECT u #>> '{}' AS url
        FROM jsonb_path_query(v.content::jsonb, 'lax $.materials[*] ? (@.kind == "VIDEO_LINK").url') AS u
        UNION ALL
        SELECT u #>> '{}'
        FROM jsonb_path_query(v.content::jsonb, 'lax $.translations.*.materials[*] ? (@.kind == "VIDEO_LINK").url') AS u
    ) l
    WHERE v.version_id = c.version_id
      AND btrim(coalesce(l.url, '')) <> ''
      AND NOT quizbuks_mainland_video_link(l.url))
WHERE c.foreign_video IS NULL;

-- The exceptions (A3-15; headquarters' for any sponsor, an operator's for its own sponsors): a publication, or all
-- of a sponsor's, that mainland China may answer although foreign, with a reason and perhaps a last day (UTC,
-- inclusive). Written by the QB_REGION_EXCEPTION_* processes only; revoked, never deleted (the platform's audit keeps
-- every change).
CREATE TABLE qb_region_exception (
    exception_id   varchar(36)   PRIMARY KEY,
    kind           varchar(12)   NOT NULL CHECK (kind IN ('PUBLICATION', 'SPONSOR')),
    publication_id varchar(36),
    sponsor_id     varchar(128),
    -- Whose it is, for the operators' entry scope (the requester, 2026-11-07): the sponsor of a SPONSOR exception,
    -- the publisher of a PUBLICATION exception's publication.
    owner_id       varchar(128)  NOT NULL,
    reason         varchar(500)  NOT NULL,
    expires_on     date,
    revoked        boolean       NOT NULL DEFAULT false,
    revoke_reason  varchar(500),
    created_at     timestamptz   NOT NULL,
    created_by     varchar(128)  NOT NULL,
    changed_at     timestamptz   NOT NULL,
    changed_by     varchar(128)  NOT NULL,
    version        bigint        NOT NULL,
    CONSTRAINT qb_region_exception_target_check CHECK (
        (kind = 'PUBLICATION' AND publication_id IS NOT NULL AND sponsor_id IS NULL)
        OR (kind = 'SPONSOR' AND sponsor_id IS NOT NULL AND publication_id IS NULL)),
    CONSTRAINT qb_region_exception_owner_check CHECK (kind <> 'SPONSOR' OR owner_id = sponsor_id),
    CONSTRAINT qb_region_exception_revoked_check CHECK (NOT revoked OR revoke_reason IS NOT NULL)
);
-- The start reads a publication's and its sponsor's (only for a foreign card from mainland China).
CREATE INDEX qb_region_exception_publication_idx ON qb_region_exception (publication_id) WHERE publication_id IS NOT NULL;
CREATE INDEX qb_region_exception_sponsor_idx ON qb_region_exception (sponsor_id) WHERE sponsor_id IS NOT NULL;
-- An operator's session reads its own sponsors' (through qb_sponsor_operator).
CREATE INDEX qb_region_exception_owner_idx ON qb_region_exception (owner_id);

-- How many starts the rule refused, by UTC day (counted in memory, written by the job qb.region-refusals): only the
-- count, never who or from which address.
CREATE TABLE qb_region_refusal_day (
    refusal_id varchar(36)    PRIMARY KEY,
    day        date           NOT NULL,
    refused    numeric(12, 0) NOT NULL CHECK (refused >= 0),
    version    bigint         NOT NULL,
    CONSTRAINT uk_qb_region_refusal_day UNIQUE (day)
);
