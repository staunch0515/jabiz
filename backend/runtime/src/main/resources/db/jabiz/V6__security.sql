-- Users, roles, permissions, menus and login records (docs/design/10-security.md; decision D12). All of them are
-- temporal platform entities: versions are only inserted (decision D5), every change belongs to an operation.

-- Creates one temporal table with the system columns, the version constraint and the lookup indexes of
-- docs/design/04-temporal-append-only.md section 2.1, followed by the entity's own columns.
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

SELECT jabiz_create_temporal_table('sec_user_version', 'user_id', '
    user_name     varchar(100) NOT NULL,
    display_name  varchar(200),
    tenant_id     varchar(100),
    enabled       boolean      NOT NULL DEFAULT true,
    password_hash varchar(100)');
CREATE INDEX sec_user_version_name_idx ON sec_user_version (user_name);

SELECT jabiz_create_temporal_table('sec_role_version', 'role_id', '
    role_code varchar(100) NOT NULL,
    labels    jsonb        NOT NULL DEFAULT ''{}''::jsonb,
    enabled   boolean      NOT NULL DEFAULT true');
CREATE INDEX sec_role_version_code_idx ON sec_role_version (role_code);

SELECT jabiz_create_temporal_table('sec_role_permission_version', 'role_permission_id', '
    role_id    uuid         NOT NULL REFERENCES entity_registry (entity_id),
    permission varchar(200) NOT NULL');
CREATE INDEX sec_role_permission_version_role_idx ON sec_role_permission_version (role_id, permission);

SELECT jabiz_create_temporal_table('sec_user_role_version', 'user_role_id', '
    user_id uuid NOT NULL REFERENCES entity_registry (entity_id),
    role_id uuid NOT NULL REFERENCES entity_registry (entity_id)');
CREATE INDEX sec_user_role_version_user_idx ON sec_user_role_version (user_id, role_id);

SELECT jabiz_create_temporal_table('sec_menu_version', 'menu_id', '
    menu_code   varchar(100) NOT NULL,
    parent_code varchar(100),
    labels      jsonb        NOT NULL DEFAULT ''{}''::jsonb,
    path        varchar(500),
    icon        varchar(100),
    sort_order  integer      NOT NULL DEFAULT 0,
    permission  varchar(200) NOT NULL,
    enabled     boolean      NOT NULL DEFAULT true');
CREATE INDEX sec_menu_version_code_idx ON sec_menu_version (menu_code);

-- One row per sign-in attempt of a known user (and per unlock). Each carries the failure counter and lock in force
-- after it, so the next attempt only needs the latest one (LoginAttemptPolicy).
SELECT jabiz_create_temporal_table('sec_login_record_version', 'login_record_id', '
    user_id       uuid         NOT NULL REFERENCES entity_registry (entity_id),
    user_name     varchar(100) NOT NULL,
    attempt_no    bigint       NOT NULL,
    outcome       varchar(30)  NOT NULL,
    failure_count integer      NOT NULL,
    locked_until  timestamptz,
    attempt_time  timestamptz  NOT NULL,
    request_id    varchar(64)');
CREATE INDEX sec_login_record_version_user_idx ON sec_login_record_version (user_id, attempt_no DESC);

DROP FUNCTION jabiz_create_temporal_table(text, text, text);

-- Refresh tokens (decision D12). Only hashes are stored. Nothing is updated: using a token inserts its use (the
-- primary key refuses a second use, which revokes the family), logging out inserts a revocation of the family.
-- Reuse detection and revocation rely on these rows staying, so the tables are guarded like the operation tables
-- (decision D5); expired rows are removed by the controlled purge only.
CREATE TABLE sec_refresh_token (
    token_hash char(64)    PRIMARY KEY,                   -- hex SHA-256 of the token
    family_id  uuid        NOT NULL,                      -- all tokens that descend from one sign-in
    user_id    uuid        NOT NULL,
    issued_at  timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX sec_refresh_token_family_idx ON sec_refresh_token (family_id);

CREATE TABLE sec_refresh_token_use (
    token_hash char(64)    PRIMARY KEY REFERENCES sec_refresh_token,
    used_at    timestamptz NOT NULL
);

CREATE TABLE sec_refresh_family_revocation (
    family_id  uuid        PRIMARY KEY,
    revoked_at timestamptz NOT NULL,
    reason     text        NOT NULL                       -- LOGOUT, REUSE, PASSWORD
);

SELECT jabiz_protect_append_only('sec_refresh_token');
SELECT jabiz_protect_append_only('sec_refresh_token_use');
SELECT jabiz_protect_append_only('sec_refresh_family_revocation');
