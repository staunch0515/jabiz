-- Publishing (docs/quizbuks/02-design.md section 3.3, docs/quizbuks/plans/Q4-publishing.md): publications and their
-- reward tiers, temporal and only ever inserted into (platform docs/design/04-temporal-append-only.md, decision D9);
-- and the counters of a publication that went on, an ordinary table that Q5 updates under the publication's lock.

CREATE FUNCTION quizbuks_create_temporal_table(p_table text, p_key text, p_columns text) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('CREATE TABLE %I (
        row_id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        %I                uuid        NOT NULL REFERENCES entity_registry (entity_id),
        version_no        integer     NOT NULL,
        effect_start_time timestamptz NOT NULL,
        created_time      timestamptz NOT NULL,
        process_seq_id    bigint      NOT NULL REFERENCES op_process (process_seq_id),
        is_deleted        boolean     NOT NULL DEFAULT false,
        %s,
        CONSTRAINT %I UNIQUE (%I, version_no))',
        p_table, p_key, p_columns, p_table || '_uk', p_key);
    EXECUTE format('CREATE INDEX %I ON %I (%I, effect_start_time DESC, version_no DESC)',
        p_table || '_current_idx', p_table, p_key);
    EXECUTE format('CREATE INDEX %I ON %I (process_seq_id)', p_table || '_process_idx', p_table);
    PERFORM jabiz_protect_append_only(p_table::regclass);
END $$;

-- Lengths are those of publishing.PublishingLimits and country.Regions; money is JPY without decimals (Kudos).
SELECT quizbuks_create_temporal_table('qb_publication_version', 'publication_id', '
    sponsor_id          varchar(128)   NOT NULL,
    quiz_id             uuid,
    version_id          uuid,
    version_label       varchar(20),
    name                varchar(100)   NOT NULL,
    regions             varchar(64),
    start_at            timestamptz,
    end_at              timestamptz,
    time_limit_sec      numeric(5, 0),
    pass_score          numeric(6, 0),
    budget_cap          numeric(19, 0),
    max_winners         numeric(9, 0),
    full_score          numeric(7, 0),
    max_reward          numeric(19, 0),
    max_spend           numeric(19, 0),
    tier_count          numeric(2, 0)  NOT NULL,
    status              varchar(20)    NOT NULL,
    reject_reason       varchar(500),
    reject_code         varchar(64),
    approval_request_id varchar(64),
    submitted_at        timestamptz,
    approved_at         timestamptz,
    paused_at           timestamptz,
    ended_at            timestamptz,
    end_reason          varchar(20),
    revision            numeric(9, 0)  NOT NULL,
    edited_at           timestamptz    NOT NULL,
    copied_from         uuid');

SELECT quizbuks_create_temporal_table('qb_reward_tier_version', 'tier_id', '
    publication_id uuid           NOT NULL,
    sponsor_id     varchar(128)   NOT NULL,
    seq            numeric(2, 0)  NOT NULL,
    from_score     numeric(6, 0)  NOT NULL,
    to_score       numeric(6, 0)  NOT NULL,
    reward         numeric(19, 0) NOT NULL');

-- The sponsor datasets limit by sponsor; removing a quiz looks up its publications; versions are looked up by the
-- publications that use them.
CREATE INDEX qb_publication_version_sponsor_idx ON qb_publication_version (sponsor_id);
CREATE INDEX qb_publication_version_quiz_idx ON qb_publication_version (quiz_id);
CREATE INDEX qb_publication_version_version_idx ON qb_publication_version (version_id);
CREATE INDEX qb_publication_version_copied_idx ON qb_publication_version (copied_from);
-- The minute's tick and the feed: publications by state and time.
CREATE INDEX qb_publication_version_start_idx ON qb_publication_version (status, start_at);
CREATE INDEX qb_publication_version_end_idx ON qb_publication_version (status, end_at);
CREATE INDEX qb_reward_tier_version_publication_idx ON qb_reward_tier_version (publication_id);
CREATE INDEX qb_reward_tier_version_sponsor_idx ON qb_reward_tier_version (sponsor_id);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);

-- One row per publication that went on, inserted with zeros when it is approved. Updated in place (Q5) under the
-- publication's lock: a version per attempt would be too many. version is the optimistic lock of ordinary entities.
CREATE TABLE qb_publication_counter (
    publication_id uuid           PRIMARY KEY REFERENCES entity_registry (entity_id),
    starts         numeric(9, 0)  NOT NULL CHECK (starts >= 0),
    completions    numeric(9, 0)  NOT NULL CHECK (completions >= 0),
    passes         numeric(9, 0)  NOT NULL CHECK (passes >= 0),
    winners        numeric(9, 0)  NOT NULL CHECK (winners >= 0),
    reserved       numeric(19, 0) NOT NULL CHECK (reserved >= 0),
    spent          numeric(19, 0) NOT NULL CHECK (spent >= 0),
    version        bigint         NOT NULL
);
