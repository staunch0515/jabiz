-- Four eyes on protected grants (docs/design/10-security.md section 16; decision D48; ROADMAP phase 16r).

-- A protected change: requested by one administrator, applied after another approves. Temporal (append-only): what
-- the change does never changes, only its status moves on.
CREATE TABLE sec_protected_change_version (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    change_id         uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    kind              varchar(20)  NOT NULL,
    user_id           varchar(36),
    role_id           varchar(36),
    permission        varchar(200),
    data_from         timestamptz,
    data_to           timestamptz,
    effective_time    timestamptz,
    provider          varchar(40),
    subject           varchar(255),
    email             varchar(320),
    password_hash     varchar(100),
    mfa_id            varchar(36),
    gained            varchar(2000),
    affected_users    varchar(8000),
    reason            varchar(500) NOT NULL,
    requested_by      varchar(100) NOT NULL,
    status            varchar(20)  NOT NULL,
    decided_by        varchar(100),
    result_id         varchar(36),
    failure           varchar(200),
    CONSTRAINT sec_protected_change_version_uk UNIQUE (change_id, version_no)
);
CREATE INDEX sec_protected_change_version_current_idx
    ON sec_protected_change_version (change_id, effect_start_time DESC, version_no DESC);
CREATE INDEX sec_protected_change_version_process_idx ON sec_protected_change_version (process_seq_id);
CREATE INDEX sec_protected_change_version_user_idx ON sec_protected_change_version (user_id);
CREATE INDEX sec_protected_change_version_role_idx ON sec_protected_change_version (role_id);
SELECT jabiz_protect_append_only('sec_protected_change_version');

-- When the setup window ended: the first time two administrators besides the first one existed. One row at most;
-- once it is there, protected changes need approval for good (only the configuration can switch them off).
CREATE TABLE sec_protected_grants_armed (
    armed_id       smallint    PRIMARY KEY CHECK (armed_id = 1),
    armed_time     timestamptz NOT NULL,
    process_seq_id bigint      NOT NULL REFERENCES op_process (process_seq_id)
);
SELECT jabiz_protect_append_only('sec_protected_grants_armed');

-- When the first administrator retired: the first time another administrator existed. One row at most; once it is
-- there the first administrator makes and decides no protected change again, whoever is disabled later.
CREATE TABLE sec_bootstrap_retired (
    retired_id     smallint    PRIMARY KEY CHECK (retired_id = 1),
    user_id        uuid        NOT NULL,
    retired_time   timestamptz NOT NULL,
    process_seq_id bigint      NOT NULL REFERENCES op_process (process_seq_id)
);
SELECT jabiz_protect_append_only('sec_bootstrap_retired');

-- Who holds a role, and which roles grant a permission: the checks of every grant look them up by these immutable
-- columns.
CREATE INDEX sec_user_role_version_role_idx ON sec_user_role_version (role_id);
CREATE INDEX sec_role_permission_version_permission_idx ON sec_role_permission_version (permission);

-- A request whose levels the platform fixes (the protected changes) names no rule.
ALTER TABLE sys_approval_request_version ALTER COLUMN rule_id DROP NOT NULL;
ALTER TABLE sys_approval_request_version ALTER COLUMN rule_version_no DROP NOT NULL;
