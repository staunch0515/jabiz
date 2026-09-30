-- Legal holds (docs/design/21-audit-retention.md section 3.3, decision D27): entries of an entity - by id, or all
-- whose field has a value - that must not be deleted while the hold is in force, whatever their retention. The
-- temporal platform entity SysLegalHold, written only by LEGAL_HOLD_PLACE and LEGAL_HOLD_RELEASE; versions are only
-- inserted (decision D5), so who placed and released a hold, when and why, stays readable.
CREATE TABLE sys_legal_hold_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hold_id           uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    hold_name         varchar(200)  NOT NULL,
    reason            varchar(2000) NOT NULL,
    entity_type       varchar(100)  NOT NULL,
    entity_ids        varchar(20000),
    match_field       varchar(100),
    match_value       varchar(500),
    status            varchar(20)   NOT NULL,
    release_reason    varchar(2000),
    CONSTRAINT sys_legal_hold_version_uk UNIQUE (hold_id, version_no)
);
CREATE INDEX sys_legal_hold_version_current_idx
    ON sys_legal_hold_version (hold_id, effect_start_time DESC, version_no DESC);
CREATE INDEX sys_legal_hold_version_process_idx ON sys_legal_hold_version (process_seq_id);
CREATE INDEX sys_legal_hold_version_entity_idx ON sys_legal_hold_version (entity_type);
SELECT jabiz_protect_append_only('sys_legal_hold_version');
