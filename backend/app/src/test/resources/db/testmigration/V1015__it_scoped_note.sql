-- Entry scopes through a mapping on a process-only dataset (ROADMAP phase 17y-7, decision D64): notes of users,
-- limited to a warehouse session's staff through WarehouseStaff, written by a process.
CREATE TABLE it_scoped_note (
    f_id      varchar(64) PRIMARY KEY,
    f_owner   varchar(36) NOT NULL,
    f_text    text,
    f_version bigint      NOT NULL DEFAULT 1
);

-- The same, append-only (decision D9): its changes are checked against the scope in the temporal writer.
CREATE TABLE it_scoped_entry (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id          uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    owner             varchar(36)  NOT NULL,
    body              text,
    CONSTRAINT it_scoped_entry_version_uk UNIQUE (entry_id, version_no)
);
CREATE INDEX it_scoped_entry_current_idx ON it_scoped_entry (entry_id, effect_start_time DESC, version_no DESC);
CREATE INDEX it_scoped_entry_process_idx ON it_scoped_entry (process_seq_id);
SELECT jabiz_protect_append_only('it_scoped_entry');
