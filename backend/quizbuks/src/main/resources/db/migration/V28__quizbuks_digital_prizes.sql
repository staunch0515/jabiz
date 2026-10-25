-- Digital prizes (docs/quizbuks/plans/Q33-digital-prize.md): a fourth reward method, DIGITAL_PRIZE, a code, voucher
-- number or link the winner gets without going anywhere: from a pool the sponsor loaded (POOL, one code each), one
-- shared code or link (SHARED), or sent by the sponsor by hand (SPONSOR_SENT). The delivery, the description and the
-- days to send are part of the frozen policy, so they are on the version and the card; the shared content is sealed
-- (SealedSecrets under the sponsor's id) and never read back.
ALTER TABLE qb_publication_version DROP CONSTRAINT qb_publication_version_reward_method_check;
ALTER TABLE qb_publication_version ADD CONSTRAINT qb_publication_version_reward_method_check
    CHECK (reward_method IN ('PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM', 'DIGITAL_PRIZE'));
ALTER TABLE qb_publication_version
    ADD COLUMN digital_delivery       varchar(20) CHECK (digital_delivery IN ('POOL', 'SHARED', 'SPONSOR_SENT')),
    ADD COLUMN digital_description    varchar(500),
    ADD COLUMN digital_send_days      numeric(3, 0),
    ADD COLUMN sealed_digital_content varchar(2000);
ALTER TABLE qb_publication_card DROP CONSTRAINT qb_publication_card_reward_method_check;
ALTER TABLE qb_publication_card ADD CONSTRAINT qb_publication_card_reward_method_check
    CHECK (reward_method IN ('PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM', 'DIGITAL_PRIZE'));
ALTER TABLE qb_publication_card
    ADD COLUMN digital_delivery       varchar(20) CHECK (digital_delivery IN ('POOL', 'SHARED', 'SPONSOR_SENT')),
    ADD COLUMN digital_description    varchar(500),
    ADD COLUMN digital_send_days      numeric(3, 0),
    ADD COLUMN sealed_digital_content varchar(2000);

-- A reward settled as a digital prize: DIGITAL (a digital prize row).
ALTER TABLE qb_attempt DROP CONSTRAINT qb_attempt_reward_status_check;
ALTER TABLE qb_attempt ADD CONSTRAINT qb_attempt_reward_status_check
    CHECK (reward_status IN ('NONE', 'PAID', 'PENDING', 'CASH', 'REDEEM', 'DIGITAL'));

-- The pool: the codes a sponsor loaded for a publication, one row each, sealed; issued from the lowest sequence to
-- the attempts that win, under the pool's lock; taken back (revoked) while the publication is not running. An
-- ordinary table: the issue and the revoke are its only changes, each recorded by its process.
CREATE TABLE qb_digital_code (
    code_id           varchar(36)   PRIMARY KEY,
    publication_id    uuid          NOT NULL REFERENCES entity_registry (entity_id),
    sponsor_id        varchar(128)  NOT NULL,
    seq               numeric(9, 0) NOT NULL,
    sealed_content    varchar(2000) NOT NULL,
    hint              varchar(5),
    status            varchar(20)   NOT NULL CHECK (status IN ('AVAILABLE', 'ISSUED', 'REVOKED')),
    loaded_at         timestamptz   NOT NULL,
    issued_attempt_id varchar(36),
    issued_at         timestamptz,
    revoked_at        timestamptz,
    version           bigint        NOT NULL,
    CONSTRAINT uk_qb_digital_code_seq UNIQUE (publication_id, seq)
);
-- The next code of a publication's pool; a code is issued to one attempt only.
CREATE INDEX qb_digital_code_available_idx ON qb_digital_code (publication_id, seq) WHERE status = 'AVAILABLE';
CREATE UNIQUE INDEX qb_digital_code_attempt_uk ON qb_digital_code (issued_attempt_id)
    WHERE issued_attempt_id IS NOT NULL;
CREATE INDEX qb_digital_code_sponsor_idx ON qb_digital_code (sponsor_id);

-- The temporal table below is made as V4's were (the helper is created for this migration and dropped after it).
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

-- A digital prize: what an attempt won, delivered at once by the system (a pool code or the shared content, sealed
-- in the row) or owed by the sponsor until send_by; confirmed by the taker, disputed and resolved by an administrator,
-- overdue when the sponsor is late. The content is sealed and sensitive; opened_at says when the winner first saw it.
SELECT quizbuks_create_temporal_table('qb_digital_prize_version', 'digital_prize_id', '
    attempt_id       varchar(36)    NOT NULL,
    publication_id   uuid           NOT NULL,
    sponsor_id       varchar(128)   NOT NULL,
    user_id          varchar(128)   NOT NULL,
    amount           numeric(19, 0) NOT NULL CHECK (amount > 0),
    description      varchar(500),
    delivery         varchar(20)    NOT NULL CHECK (delivery IN (''POOL'', ''SHARED'', ''SPONSOR_SENT'')),
    status           varchar(20)    NOT NULL
        CHECK (status IN (''DUE'', ''DELIVERED'', ''CONFIRMED'', ''DISPUTED'', ''OVERDUE'', ''VOID'')),
    due_at           timestamptz    NOT NULL,
    send_by          timestamptz,
    reminded_at      timestamptz,
    delivered_at     timestamptz,
    delivered_by     varchar(10),
    sealed_content   varchar(2000),
    hint             varchar(5),
    opened_at        timestamptz,
    confirm_by       timestamptz,
    confirmed_at     timestamptz,
    disputed_at      timestamptz,
    dispute_note     varchar(500),
    overdue_at       timestamptz,
    resolved_at      timestamptz,
    resolved_by      varchar(128),
    resolution       varchar(20) CHECK (resolution IN (''DELIVERED'', ''UNDELIVERED'', ''VOID'')),
    resolve_note     varchar(500),
    revision         numeric(9, 0)  NOT NULL');
CREATE INDEX qb_digital_prize_version_attempt_idx ON qb_digital_prize_version (attempt_id);
CREATE INDEX qb_digital_prize_version_sponsor_idx ON qb_digital_prize_version (sponsor_id);
CREATE INDEX qb_digital_prize_version_user_idx ON qb_digital_prize_version (user_id);
CREATE INDEX qb_digital_prize_version_publication_idx ON qb_digital_prize_version (publication_id);
-- The jobs (Q33b): prizes owed past their time to send; prizes delivered by hand past the taker's time to confirm.
CREATE INDEX qb_digital_prize_version_send_by_idx ON qb_digital_prize_version (send_by) WHERE status = 'DUE';

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
