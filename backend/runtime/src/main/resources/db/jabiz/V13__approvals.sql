-- Approvals and segregation of duties (docs/design/18-numbering-approvals-tasks.md sections 3 and 4; decision D23).
-- Rules, approver limits, SoD rules, their proposed changes and the approval requests are temporal platform entities
-- (versions are only inserted, decision D5); decisions and evaluations are append-only records.

-- The helper of V6 (dropped there, dropped again below): creates one temporal table with the system columns, the
-- version constraint and the lookup indexes of docs/design/04-temporal-append-only.md section 2.1.
CREATE FUNCTION jabiz_create_temporal_table(p_table text, p_key text, p_columns text) RETURNS void
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

-- When a subject needs approval and by whom: condition and levels are JSON (ApprovalCondition, ApprovalLevel).
SELECT jabiz_create_temporal_table('sys_approval_rule_version', 'rule_id', '
    rule_code   varchar(100)  NOT NULL,
    subject     varchar(100)  NOT NULL,
    condition   varchar(20000) NOT NULL,
    levels      varchar(4000) NOT NULL,
    priority    integer       NOT NULL,
    enabled     boolean       NOT NULL DEFAULT true,
    description varchar(500)');
CREATE INDEX sys_approval_rule_version_subject_idx ON sys_approval_rule_version (subject);

-- Up to which value of a level's limit fact a user may approve a subject.
SELECT jabiz_create_temporal_table('sys_approval_limit_version', 'limit_id', '
    user_id   uuid          NOT NULL REFERENCES entity_registry (entity_id),
    subject   varchar(100)  NOT NULL,
    max_value numeric(19,4) NOT NULL CHECK (max_value >= 0)');
CREATE INDEX sys_approval_limit_version_user_idx ON sys_approval_limit_version (user_id, subject);

-- Pairs of permission groups nobody may hold together.
SELECT jabiz_create_temporal_table('sys_sod_rule_version', 'rule_id', '
    rule_code         varchar(100)  NOT NULL,
    left_permissions  varchar(4000) NOT NULL,
    right_permissions varchar(4000) NOT NULL,
    enabled           boolean       NOT NULL DEFAULT true,
    description       varchar(500)');

-- A proposed change of a rule, limit or SoD rule; another person publishes it.
SELECT jabiz_create_temporal_table('sys_control_change_version', 'change_id', '
    target_entity  varchar(100)   NOT NULL,
    target_id      varchar(36),
    change_action  varchar(10)    NOT NULL,
    change_values  varchar(30000),
    effective_time timestamptz,
    reason         varchar(500)   NOT NULL,
    status         varchar(20)    NOT NULL,
    proposed_by    varchar(100)   NOT NULL,
    published_by   varchar(100)');
CREATE INDEX sys_control_change_version_status_idx ON sys_control_change_version (status);

-- One request per case that needs approval, bound to the content hash it was prepared with.
SELECT jabiz_create_temporal_table('sys_approval_request_version', 'request_id', '
    subject         varchar(100)  NOT NULL,
    entity_id       varchar(100)  NOT NULL,
    status          varchar(20)   NOT NULL,
    preparer_id     varchar(100)  NOT NULL,
    rule_id         varchar(36)   NOT NULL,
    rule_version_no integer       NOT NULL,
    content_hash    char(64)      NOT NULL,
    levels          varchar(4000) NOT NULL,
    current_level   integer       NOT NULL,
    facts           varchar(20000) NOT NULL');
CREATE INDEX sys_approval_request_version_case_idx ON sys_approval_request_version (subject, entity_id);

DROP FUNCTION jabiz_create_temporal_table(text, text, text);

-- Every decision on a request: one per level, one per approver (nobody decides two levels of a request).
CREATE TABLE sys_approval_decision (
    decision_id    varchar(36)  PRIMARY KEY,
    request_id     uuid         NOT NULL REFERENCES entity_registry (entity_id),
    level_no       integer      NOT NULL,
    approver_id    varchar(100) NOT NULL,
    decision       varchar(10)  NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    reason         varchar(500),
    decided_time   timestamptz  NOT NULL,
    process_seq_id bigint       NOT NULL REFERENCES op_process (process_seq_id),
    version        bigint       NOT NULL,
    CONSTRAINT sys_approval_decision_level_key UNIQUE (request_id, level_no),
    CONSTRAINT sys_approval_decision_approver_key UNIQUE (request_id, approver_id)
);
CREATE INDEX sys_approval_decision_process_idx ON sys_approval_decision (process_seq_id);
SELECT jabiz_protect_append_only('sys_approval_decision');

-- Every evaluation of a case: the outcome, the rule versions considered and the facts (the impact preview replays
-- them against draft rules).
CREATE TABLE sys_approval_evaluation (
    evaluation_id  varchar(36)    PRIMARY KEY,
    subject        varchar(100)   NOT NULL,
    entity_id      varchar(100)   NOT NULL,
    outcome        varchar(20)    NOT NULL,
    matched_rule   varchar(100),
    rule_versions  varchar(20000) NOT NULL,
    request_id     varchar(36),
    content_hash   char(64)       NOT NULL,
    facts          varchar(20000) NOT NULL,
    business_time  timestamptz    NOT NULL,
    evaluated_time timestamptz    NOT NULL,
    process_seq_id bigint         NOT NULL REFERENCES op_process (process_seq_id),
    version        bigint         NOT NULL
);
CREATE INDEX sys_approval_evaluation_case_idx ON sys_approval_evaluation (subject, entity_id, evaluated_time DESC);
CREATE INDEX sys_approval_evaluation_process_idx ON sys_approval_evaluation (process_seq_id);
SELECT jabiz_protect_append_only('sys_approval_evaluation');
