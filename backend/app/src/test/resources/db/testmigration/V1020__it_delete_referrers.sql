-- Referrers of ItPrice for the deletions read ahead together (ROADMAP 17zz21; see ItDeleteFixtures).

-- A temporal referrer whose versions may be scheduled, publishing its changes.
CREATE TABLE it_tag (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tag_id            uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    price_ref         uuid         REFERENCES entity_registry (entity_id),
    label             text,
    CONSTRAINT it_tag_version_uk UNIQUE (tag_id, version_no)
);
CREATE INDEX it_tag_current_idx ON it_tag (tag_id, effect_start_time DESC, version_no DESC);
CREATE INDEX it_tag_process_idx ON it_tag (process_seq_id);
CREATE INDEX it_tag_price_idx ON it_tag (price_ref);
SELECT jabiz_protect_append_only('it_tag');

-- A plain referrer, which may hold very many references to one price.
CREATE TABLE it_price_mark (
    f_id        varchar(64) PRIMARY KEY,
    f_price_ref uuid,
    f_version   bigint      NOT NULL DEFAULT 1
);
CREATE INDEX it_price_mark_price_idx ON it_price_mark (f_price_ref);
