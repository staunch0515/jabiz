-- The audit trail with values (docs/design/21-audit-retention.md section 1, decision D27): one row per written
-- version or row of any entity - temporal or not - with what changed, field by field, before and after (secrets
-- masked), who, when and why. The operation tables keep names and identifiers only (docs/design/11 section 3); this
-- keeps the values, also of entities changed in place, whose earlier values are otherwise lost. Append-only
-- (decision D5): not changeable by anyone, administrators included, short of switching the guard off.
CREATE TABLE sys_audit_record (
    record_no         bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    process_seq_id    bigint        REFERENCES op_process (process_seq_id),
    entity_type       varchar(100)  NOT NULL,
    entity_id         varchar(200)  NOT NULL,
    action            varchar(20)   NOT NULL,
    version_no        bigint,
    effect_start_time timestamptz,
    changes           text          NOT NULL,
    changed_fields    text[]        NOT NULL,
    actor_id          varchar(100)  NOT NULL,
    recorded_time     timestamptz   NOT NULL,
    reason            varchar(2000)
);
CREATE INDEX sys_audit_record_entity_idx ON sys_audit_record (entity_type, entity_id, recorded_time DESC);
CREATE INDEX sys_audit_record_time_idx ON sys_audit_record (recorded_time DESC, record_no DESC);
CREATE INDEX sys_audit_record_actor_idx ON sys_audit_record (actor_id, recorded_time DESC);
CREATE INDEX sys_audit_record_process_idx ON sys_audit_record (process_seq_id);
SELECT jabiz_protect_append_only('sys_audit_record');
