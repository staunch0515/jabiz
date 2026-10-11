-- Accounts (docs/quizbuks/02-design.md section 3.1, docs/quizbuks/plans/Q2-accounts.md): a person's profile, a
-- sponsor's onboarding and a user's ban, temporal and only ever inserted into (platform
-- docs/design/04-temporal-append-only.md, decision D9). One instance per user: the platform's user id is the column
-- user_id, unique among current versions (the platform's temporal uniqueness check, decision D6), and the key every
-- process and the sign-in guard look the rows up by. Profiles and onboarding keep the caller's id as text (as Q4's
-- sponsor_id); a ban refers to a platform user (a UUID, checked by the platform on write; no foreign key, as other
-- references to temporal entities in QuizBuks).

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

-- Lengths are those of identity.AccountLimits.
SELECT quizbuks_create_temporal_table('qb_profile_version', 'profile_id', '
    user_id            varchar(128)  NOT NULL,
    display_name       varchar(100)  NOT NULL,
    country_code       varchar(2),
    city               varchar(100),
    preferred_currency varchar(10),
    created_at         timestamptz   NOT NULL,
    edited_at          timestamptz   NOT NULL,
    revision           numeric(9, 0) NOT NULL');

SELECT quizbuks_create_temporal_table('qb_sponsor_version', 'onboarding_id', '
    user_id             varchar(128)  NOT NULL,
    contact_name        varchar(100)  NOT NULL,
    company             varchar(100)  NOT NULL,
    intro               varchar(2000),
    avatar              uuid,
    review_status       varchar(20)   NOT NULL,
    terms_accepted_at   timestamptz,
    approval_request_id varchar(64),
    submitted_at        timestamptz,
    approved_at         timestamptz,
    reject_reason       varchar(500),
    rejected_at         timestamptz,
    revision            numeric(9, 0) NOT NULL,
    edited_at           timestamptz   NOT NULL');

SELECT quizbuks_create_temporal_table('qb_user_ban_version', 'ban_id', '
    user_id    uuid          NOT NULL,
    banned     boolean       NOT NULL,
    reason     varchar(500),
    banned_at  timestamptz,
    banned_by  varchar(128),
    lifted_at  timestamptz,
    lifted_by  varchar(128),
    lift_note  varchar(500),
    revision   numeric(9, 0) NOT NULL');

-- The user is the only lookup (immutable, so it reaches the index; decision D29). Conditions on country, state or
-- company scan current versions in the administrators' templates (the plan's risk on scale).
CREATE INDEX qb_profile_version_user_idx ON qb_profile_version (user_id);
CREATE INDEX qb_sponsor_version_user_idx ON qb_sponsor_version (user_id);
CREATE INDEX qb_user_ban_version_user_idx ON qb_user_ban_version (user_id);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
