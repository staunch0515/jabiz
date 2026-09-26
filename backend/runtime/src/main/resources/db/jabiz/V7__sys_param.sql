-- Business parameters with effective times (docs/design/04-temporal-append-only.md section 9, decision D13): the
-- temporal platform entity SysParam. A parameter's kind is stored next to its value, in the notation of SQL template
-- headers; values are canonical text. Versions are only inserted (decision D5).
CREATE TABLE sys_param_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    param_id          uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    param_key         varchar(200)  NOT NULL,
    value_kind        jsonb         NOT NULL,
    param_value       varchar(4000) NOT NULL,
    description       varchar(500),
    CONSTRAINT sys_param_version_uk UNIQUE (param_id, version_no)
);
CREATE INDEX sys_param_version_current_idx
    ON sys_param_version (param_id, effect_start_time DESC, version_no DESC);
CREATE INDEX sys_param_version_process_idx ON sys_param_version (process_seq_id);
CREATE INDEX sys_param_version_key_idx ON sys_param_version (param_key);
SELECT jabiz_protect_append_only('sys_param_version');
