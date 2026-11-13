-- Many rows per user for the loads of the notices before an account is deleted (ROADMAP 17zz22; see ItErasureFixtures).
CREATE TABLE it_erasure_point (
    f_id      varchar(64)   PRIMARY KEY,
    f_user    varchar(36),
    f_status  varchar(10),
    f_region  varchar(10),
    f_amount  numeric(19, 0),
    f_note    varchar(100),
    f_flag    boolean,
    f_title   jsonb,
    f_entry   uuid,
    f_secret  varchar(100),
    f_card    varchar(30),
    f_version bigint        NOT NULL DEFAULT 1
);
CREATE INDEX it_erasure_point_user_idx ON it_erasure_point (f_user);

-- A temporal entity of the user's: two versions of one entry and a deleted one count once.
CREATE TABLE it_erasure_entry (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id          uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    user_ref          varchar(36),
    amount            numeric(19, 0),
    CONSTRAINT it_erasure_entry_version_uk UNIQUE (entry_id, version_no)
);
CREATE INDEX it_erasure_entry_current_idx ON it_erasure_entry (entry_id, effect_start_time DESC, version_no DESC);
CREATE INDEX it_erasure_entry_process_idx ON it_erasure_entry (process_seq_id);
CREATE INDEX it_erasure_entry_user_idx ON it_erasure_entry (user_ref);
SELECT jabiz_protect_append_only('it_erasure_entry');
