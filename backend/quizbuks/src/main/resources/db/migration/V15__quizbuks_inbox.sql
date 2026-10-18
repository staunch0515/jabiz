-- Q7c (docs/quizbuks/plans/Q7-messages-legal.md D-Q7-7): the in-app inbox and the announcements. The order agreed is
-- Q7b V12, Q7a V13, Q9e1 V14, Q7c V15, Q8a V16; an installation is migrated in that order (Flyway refuses a lower
-- version after a higher one), so V14 is deployed before this one.
--  * qb_message: personal messages (a reward paid or pending, a transfer, the payout account, a reminder), made by the
--    event consumers and the daily job, their texts in every language (jsonb, decision D20). Ordinary: many rows that
--    never change but for when they were read and clicked; one per source_key, so a replayed event makes none again;
--  * qb_broadcast_version: announcements and sponsors' notices, temporal (the record of who wrote, sent and withdrew
--    them; platform decision D9);
--  * qb_broadcast_target: the named users of an announcement;
--  * qb_broadcast_feed: the sent ones, the only table the inbox matches against (never a row per reader); live is
--    false once withdrawn, expired or its publication ended (the minute's job qb.broadcast-expire), so the inbox reads
--    only the few current ones through a partial index, however many were ever sent (review H1);
--  * qb_broadcast_schedule: a scheduled one's due time;
--  * qb_broadcast_receipt: a user's first reading and click of an announcement (M-61);
--  * qb_broadcast_stats: the daily snapshot of a sponsor's notice's statistics, all the sponsor sees of them
--    (review M2: live counts beside the reward rows would tell who clicked);
--  * qb_inbox_state: a user's "all read" mark, their read-through mark (every broadcast sent before it was read one
--    by one: the badge looks at the later ones only, review H1) and whether they turned promotions off;
--  * qb_attempt_start: who started which publication when (a view of qb_attempt without answers or money), for the
--    conversions of the statistics.
-- Generated keys of ordinary entities are text (UUIDv7); keys that are a broadcast's or a publication's refer to
-- entity_registry.

CREATE TABLE qb_message (
    message_id     varchar(36)  PRIMARY KEY,
    recipient_id   varchar(128) NOT NULL,
    type           varchar(10)  NOT NULL CHECK (type IN ('SYSTEM', 'REWARD', 'REMINDER')),
    kind           varchar(30)  NOT NULL,
    title          jsonb        NOT NULL,
    body           jsonb        NOT NULL,
    publication_id uuid,
    action         varchar(20),
    action_id      varchar(36),
    source_key     varchar(200) NOT NULL,
    created_at     timestamptz  NOT NULL,
    read_at        timestamptz,
    clicked_at     timestamptz,
    version        bigint       NOT NULL,
    CONSTRAINT uk_qb_message_source UNIQUE (source_key)
);
-- A user's inbox, newest first; their unread ones (the badge); the reminder job's last reminder of a user.
CREATE INDEX qb_message_recipient_idx ON qb_message (recipient_id, created_at DESC);
CREATE INDEX qb_message_unread_idx ON qb_message (recipient_id, created_at) WHERE read_at IS NULL;

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

SELECT quizbuks_create_temporal_table('qb_broadcast_version', 'broadcast_id', '
    sender_kind     varchar(10)   NOT NULL,
    sender_id       varchar(128)  NOT NULL,
    audience        varchar(10)   NOT NULL,
    type            varchar(10)   NOT NULL,
    title           jsonb,
    body            jsonb,
    target_mode     varchar(10)   NOT NULL,
    regions         varchar(64),
    target_count    numeric(5, 0),
    publication_id  uuid          REFERENCES entity_registry (entity_id),
    scheduled_at    timestamptz,
    expires_at      timestamptz,
    status          varchar(10)   NOT NULL,
    sent_at         timestamptz,
    audience_count  numeric(9, 0),
    cancel_reason   varchar(64),
    withdrawn_at    timestamptz,
    withdrawn_by    varchar(10)   CHECK (withdrawn_by IN (''SENDER'', ''ADMIN'', ''SYSTEM'')),
    withdraw_reason varchar(1000),
    source_key      varchar(100),
    revision        numeric(9, 0) NOT NULL,
    edited_at       timestamptz   NOT NULL');

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
-- A sponsor's notices are found by their sender, an announcement of a legal text by its source (immutable, decision
-- D29). Who of the administrators withdrew one is the audit's (its actor), not a field a sponsor's dataset shows
-- (review L3): withdrawn_by says only by whom in kind.
CREATE INDEX qb_broadcast_version_sender_idx ON qb_broadcast_version (sender_id);
CREATE INDEX qb_broadcast_version_source_idx ON qb_broadcast_version (source_key) WHERE source_key IS NOT NULL;

CREATE TABLE qb_broadcast_target (
    target_id    varchar(36)  PRIMARY KEY,
    broadcast_id uuid         NOT NULL REFERENCES entity_registry (entity_id),
    user_id      varchar(128) NOT NULL,
    version      bigint       NOT NULL,
    CONSTRAINT uk_qb_broadcast_target UNIQUE (broadcast_id, user_id)
);
CREATE INDEX qb_broadcast_target_user_idx ON qb_broadcast_target (user_id);

CREATE TABLE qb_broadcast_feed (
    broadcast_id   uuid         PRIMARY KEY REFERENCES entity_registry (entity_id),
    sender_kind    varchar(10)  NOT NULL,
    sender_id      varchar(128) NOT NULL,
    audience       varchar(10)  NOT NULL,
    type           varchar(10)  NOT NULL,
    target_mode    varchar(10)  NOT NULL,
    regions        varchar(64),
    publication_id uuid,
    title          jsonb        NOT NULL,
    body           jsonb        NOT NULL,
    sent_at        timestamptz  NOT NULL,
    expires_at     timestamptz  NOT NULL CHECK (expires_at > sent_at),
    live           boolean      NOT NULL,
    version        bigint       NOT NULL
);
-- The inbox and the expiry job read the live ones of an audience by their end (review H1: the live ones are only the
-- current ones); a ban withdraws a sponsor's (live is bound as a parameter there, so the index is not partial).
CREATE INDEX qb_broadcast_feed_live_idx ON qb_broadcast_feed (audience, expires_at) WHERE live;
CREATE INDEX qb_broadcast_feed_sender_idx ON qb_broadcast_feed (sender_id, live);

CREATE TABLE qb_broadcast_schedule (
    broadcast_id uuid        PRIMARY KEY REFERENCES entity_registry (entity_id),
    due_at       timestamptz NOT NULL,
    version      bigint      NOT NULL
);
CREATE INDEX qb_broadcast_schedule_due_idx ON qb_broadcast_schedule (due_at);

CREATE TABLE qb_broadcast_receipt (
    receipt_id   varchar(36)  PRIMARY KEY,
    user_id      varchar(128) NOT NULL,
    broadcast_id uuid         NOT NULL REFERENCES entity_registry (entity_id),
    read_at      timestamptz,
    clicked_at   timestamptz,
    version      bigint       NOT NULL,
    CONSTRAINT uk_qb_broadcast_receipt UNIQUE (user_id, broadcast_id)
);
-- The statistics count a broadcast's receipts.
CREATE INDEX qb_broadcast_receipt_broadcast_idx ON qb_broadcast_receipt (broadcast_id);

CREATE TABLE qb_broadcast_stats (
    broadcast_id  uuid          PRIMARY KEY REFERENCES entity_registry (entity_id),
    reads         numeric(9, 0) NOT NULL,
    clicks        numeric(9, 0) NOT NULL,
    conversions   numeric(9, 0) NOT NULL,
    snapshot_at   timestamptz   NOT NULL,
    version       bigint        NOT NULL
);

CREATE TABLE qb_inbox_state (
    user_id         varchar(128) PRIMARY KEY,
    read_all_before timestamptz,
    read_through    timestamptz,
    promo_off       boolean      NOT NULL,
    version         bigint       NOT NULL
);

-- Who started which publication when; the conversions look a reader up by (user, publication), the attempts' unique
-- pair.
CREATE VIEW qb_attempt_start AS
    SELECT attempt_id, user_id, publication_id, started_at FROM qb_attempt;
