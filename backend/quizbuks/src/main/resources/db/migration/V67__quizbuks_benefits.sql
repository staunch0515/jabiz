-- Q89a (docs/quizbuks/plans/Q89-sponsored-benefits.md): sponsored benefits between two sponsors. A provider lists a
-- benefit; a sponsor talks with it and records what it bought and paid; the provider confirms. No money moves on the
-- platform for any of it. The benefits and conversations are ordinary tables written by the processes only (their
-- history is the platform's audit); a purchase is temporal: every revision a provider confirmed stays.

CREATE TABLE qb_benefit_offer (
    offer_id        varchar(36)    PRIMARY KEY,
    provider_id     varchar(128)   NOT NULL,
    kind            varchar(10)    NOT NULL CHECK (kind IN ('GOODS', 'SERVICE', 'DIGITAL')),
    name            varchar(100)   NOT NULL,
    description     varchar(2000)  NOT NULL,
    image_1         uuid,
    image_2         uuid,
    image_3         uuid,
    image_4         uuid,
    unit_value      numeric(19, 0) NOT NULL CHECK (unit_value >= 1),
    pickup_address  varchar(500),
    pickup_hours    varchar(200),
    terms           varchar(1000),
    status          varchar(10)    NOT NULL CHECK (status IN ('LISTED', 'UNLISTED', 'TAKEN_DOWN')),
    edited_at       timestamptz    NOT NULL,
    revision        numeric(9, 0)  NOT NULL,
    version         bigint         NOT NULL
);

-- A provider's own list (and the count under its lock); the catalog of the listed ones; the image references.
CREATE INDEX qb_benefit_offer_provider_idx ON qb_benefit_offer (provider_id);
CREATE INDEX qb_benefit_offer_listed_idx ON qb_benefit_offer (status, edited_at DESC);
CREATE INDEX qb_benefit_offer_image_1_idx ON qb_benefit_offer (image_1);
CREATE INDEX qb_benefit_offer_image_2_idx ON qb_benefit_offer (image_2);
CREATE INDEX qb_benefit_offer_image_3_idx ON qb_benefit_offer (image_3);
CREATE INDEX qb_benefit_offer_image_4_idx ON qb_benefit_offer (image_4);

CREATE TABLE qb_benefit_conversation (
    conversation_id   varchar(36)   PRIMARY KEY,
    offer_id          varchar(36)   NOT NULL REFERENCES qb_benefit_offer (offer_id),
    sponsor_id        varchar(128)  NOT NULL,
    provider_id       varchar(128)  NOT NULL,
    opened_at         timestamptz   NOT NULL,
    last_message_at   timestamptz   NOT NULL,
    last_sender       varchar(10)   NOT NULL CHECK (last_sender IN ('SPONSOR', 'PROVIDER')),
    sponsor_read_at   timestamptz,
    provider_read_at  timestamptz,
    version           bigint        NOT NULL,
    CONSTRAINT qb_benefit_conversation_one UNIQUE (offer_id, sponsor_id)
);

CREATE INDEX qb_benefit_conversation_sponsor_idx ON qb_benefit_conversation (sponsor_id, last_message_at DESC);
CREATE INDEX qb_benefit_conversation_provider_idx ON qb_benefit_conversation (provider_id, last_message_at DESC);

-- The body is emptied when its sender's account is erased (the row stays, so the other side sees one was there).
CREATE TABLE qb_benefit_message (
    message_id       varchar(36)   PRIMARY KEY,
    conversation_id  varchar(36)   NOT NULL REFERENCES qb_benefit_conversation (conversation_id),
    sponsor_id       varchar(128)  NOT NULL,
    provider_id      varchar(128)  NOT NULL,
    sender           varchar(10)   NOT NULL CHECK (sender IN ('SPONSOR', 'PROVIDER')),
    sender_user_id   varchar(128)  NOT NULL,
    body             varchar(2000),
    created_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL
);

CREATE INDEX qb_benefit_message_conversation_idx ON qb_benefit_message (conversation_id, created_at);
CREATE INDEX qb_benefit_message_sender_idx ON qb_benefit_message (sender_user_id, created_at);
CREATE INDEX qb_benefit_message_sponsor_idx ON qb_benefit_message (sponsor_id);

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

-- The benefit as it was saved (what the provider confirms), what the sponsor says it bought and paid, the proofs.
SELECT quizbuks_create_temporal_table('qb_benefit_purchase_version', 'purchase_id', '
    offer_id            varchar(36)    NOT NULL,
    sponsor_id          varchar(128)   NOT NULL,
    provider_id         varchar(128)   NOT NULL,
    kind                varchar(10)    NOT NULL CHECK (kind IN (''GOODS'', ''SERVICE'', ''DIGITAL'')),
    title               varchar(100)   NOT NULL,
    unit_value          numeric(19, 0) NOT NULL CHECK (unit_value >= 1),
    pickup_address      varchar(500),
    pickup_hours        varchar(200),
    terms               varchar(1000),
    quantity            numeric(9, 0)  NOT NULL CHECK (quantity >= 1),
    paid_amount         numeric(19, 0) NOT NULL CHECK (paid_amount >= 0),
    paid_on             date           NOT NULL,
    proof_1             uuid,
    proof_2             uuid,
    proof_3             uuid,
    proof_4             uuid,
    proof_5             uuid,
    note                varchar(1000),
    status              varchar(20)    NOT NULL CHECK (status IN (''DRAFT'', ''PENDING_PROVIDER'', ''CONFIRMED'',
                                                                  ''SUSPENDED'', ''CLOSED'', ''CANCELLED'')),
    submitted_at        timestamptz,
    confirmed_at        timestamptz,
    confirmed_by        varchar(128),
    confirmed_revision  numeric(9, 0),
    declined_at         timestamptz,
    decline_reason      varchar(500),
    cancelled_at        timestamptz,
    edited_at           timestamptz    NOT NULL,
    revision            numeric(9, 0)  NOT NULL');

-- By the immutable fields only (a mutable one's condition reads every version): each side's purchases, a benefit's,
-- and the proofs' references.
CREATE INDEX qb_benefit_purchase_version_sponsor_idx ON qb_benefit_purchase_version (sponsor_id);
CREATE INDEX qb_benefit_purchase_version_provider_idx ON qb_benefit_purchase_version (provider_id);
CREATE INDEX qb_benefit_purchase_version_offer_idx ON qb_benefit_purchase_version (offer_id);
CREATE INDEX qb_benefit_purchase_version_proof_1_idx ON qb_benefit_purchase_version (proof_1);
CREATE INDEX qb_benefit_purchase_version_proof_2_idx ON qb_benefit_purchase_version (proof_2);
CREATE INDEX qb_benefit_purchase_version_proof_3_idx ON qb_benefit_purchase_version (proof_3);
CREATE INDEX qb_benefit_purchase_version_proof_4_idx ON qb_benefit_purchase_version (proof_4);
CREATE INDEX qb_benefit_purchase_version_proof_5_idx ON qb_benefit_purchase_version (proof_5);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
