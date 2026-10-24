-- Reward methods (docs/quizbuks/plans/Q22-reward-methods.md): how a publication's reward reaches the taker. PLATFORM
-- is what every publication so far does (Kudos from the sponsor's wallet); SPONSOR_CASH is cash the sponsor hands the
-- taker, recorded as a cash reward both sides confirm; OFFLINE_REDEEM is a prize the taker collects at the sponsor's
-- place (the redemption itself is Q22b). The method is part of the frozen policy, so it is on the version and the card.
ALTER TABLE qb_publication_version
    ADD COLUMN reward_method      varchar(20)  NOT NULL DEFAULT 'PLATFORM'
        CHECK (reward_method IN ('PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM')),
    ADD COLUMN redeem_description varchar(500);
ALTER TABLE qb_publication_version ALTER COLUMN reward_method DROP DEFAULT;
ALTER TABLE qb_publication_card
    ADD COLUMN reward_method      varchar(20)  NOT NULL DEFAULT 'PLATFORM'
        CHECK (reward_method IN ('PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM')),
    ADD COLUMN redeem_description varchar(500);
ALTER TABLE qb_publication_card ALTER COLUMN reward_method DROP DEFAULT;
-- The feed filters on the method of the live cards.
CREATE INDEX qb_publication_card_method_idx ON qb_publication_card (reward_method, status);

-- A reward settled outside the wallet: CASH (a cash reward row), REDEEM (a redemption row).
ALTER TABLE qb_attempt DROP CONSTRAINT qb_attempt_reward_status_check;
ALTER TABLE qb_attempt ADD CONSTRAINT qb_attempt_reward_status_check
    CHECK (reward_status IN ('NONE', 'PAID', 'PENDING', 'CASH', 'REDEEM'));

-- The temporal tables below are made as V4's were (the helper is created for this migration and dropped after it).
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

-- Which methods a sponsor may publish with, set by an administrator; a sponsor without a row has PLATFORM only.
SELECT quizbuks_create_temporal_table('qb_sponsor_reward_methods_version', 'setting_id', '
    user_id    varchar(128)  NOT NULL,
    methods    varchar(64)   NOT NULL,
    note       varchar(500),
    set_by     varchar(128)  NOT NULL,
    set_at     timestamptz   NOT NULL,
    revision   numeric(9, 0) NOT NULL');
CREATE INDEX qb_sponsor_reward_methods_version_user_idx ON qb_sponsor_reward_methods_version (user_id);

-- A cash reward: what the sponsor owes the taker for one attempt, marked paid by the sponsor and confirmed by the
-- taker (complete once both did); disputed by the taker and resolved by an administrator; overdue when the taker
-- neither confirmed nor disputed in time (they cannot start new attempts until they do). The code is what the two
-- say to each other at the counter. Temporal: its versions are the record of who did what when.
SELECT quizbuks_create_temporal_table('qb_cash_reward_version', 'cash_reward_id', '
    attempt_id       varchar(36)    NOT NULL,
    publication_id   uuid           NOT NULL,
    sponsor_id       varchar(128)   NOT NULL,
    user_id          varchar(128)   NOT NULL,
    amount           numeric(19, 0) NOT NULL CHECK (amount > 0),
    code             varchar(16)    NOT NULL,
    status           varchar(20)    NOT NULL
        CHECK (status IN (''DUE'', ''MARKED_PAID'', ''CONFIRMED'', ''DISPUTED'', ''OVERDUE'', ''VOID'')),
    due_at           timestamptz    NOT NULL,
    pay_by           timestamptz    NOT NULL,
    reminded_at      timestamptz,
    marked_paid_at   timestamptz,
    marked_paid_note varchar(200),
    confirm_by       timestamptz,
    confirmed_at     timestamptz,
    disputed_at      timestamptz,
    dispute_note     varchar(500),
    overdue_at       timestamptz,
    resolved_at      timestamptz,
    resolved_by      varchar(128),
    resolution       varchar(20) CHECK (resolution IN (''PAID'', ''UNPAID'', ''VOID'')),
    resolve_note     varchar(500),
    revision         numeric(9, 0)  NOT NULL');
CREATE INDEX qb_cash_reward_version_attempt_idx ON qb_cash_reward_version (attempt_id);
CREATE INDEX qb_cash_reward_version_sponsor_idx ON qb_cash_reward_version (sponsor_id);
CREATE INDEX qb_cash_reward_version_user_idx ON qb_cash_reward_version (user_id);
CREATE INDEX qb_cash_reward_version_publication_idx ON qb_cash_reward_version (publication_id);

-- A redemption: the prize a taker may collect at the sponsor's place for one attempt, until it expires. Q22a records
-- it; Q22b lets the taker claim it by scanning the sponsor's code.
SELECT quizbuks_create_temporal_table('qb_redemption_version', 'redemption_id', '
    attempt_id       varchar(36)    NOT NULL,
    publication_id   uuid           NOT NULL,
    sponsor_id       varchar(128)   NOT NULL,
    user_id          varchar(128)   NOT NULL,
    amount           numeric(19, 0) NOT NULL CHECK (amount > 0),
    description      varchar(500)   NOT NULL,
    code             varchar(16)    NOT NULL,
    status           varchar(20)    NOT NULL CHECK (status IN (''DUE'', ''REDEEMED'', ''EXPIRED'', ''VOID'')),
    due_at           timestamptz    NOT NULL,
    expires_at       timestamptz    NOT NULL,
    redeemed_at      timestamptz,
    revision         numeric(9, 0)  NOT NULL');
CREATE INDEX qb_redemption_version_attempt_idx ON qb_redemption_version (attempt_id);
CREATE INDEX qb_redemption_version_sponsor_idx ON qb_redemption_version (sponsor_id);
CREATE INDEX qb_redemption_version_user_idx ON qb_redemption_version (user_id);
CREATE INDEX qb_redemption_version_publication_idx ON qb_redemption_version (publication_id);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
