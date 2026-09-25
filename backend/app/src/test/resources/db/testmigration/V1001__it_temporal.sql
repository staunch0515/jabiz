-- Temporal fixtures (docs/design/04-temporal-append-only.md section 2.1; see ItTemporalFixtures).

CREATE TABLE it_price (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    price_id          uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    sku               varchar(32)  NOT NULL,
    region            varchar(8)   NOT NULL,
    amount            numeric(19,0),
    note              varchar(200),
    status            varchar(16),
    CONSTRAINT it_price_version_uk UNIQUE (price_id, version_no)
);
CREATE INDEX it_price_current_idx ON it_price (price_id, effect_start_time DESC, version_no DESC);
CREATE INDEX it_price_process_idx ON it_price (process_seq_id);
SELECT jabiz_protect_append_only('it_price');

CREATE TABLE it_note (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    note_id           uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    price_ref         uuid         REFERENCES entity_registry (entity_id),
    body              text,
    CONSTRAINT it_note_version_uk UNIQUE (note_id, version_no)
);
CREATE INDEX it_note_current_idx ON it_note (note_id, effect_start_time DESC, version_no DESC);
CREATE INDEX it_note_process_idx ON it_note (process_seq_id);
SELECT jabiz_protect_append_only('it_note');
