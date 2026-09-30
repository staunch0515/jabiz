-- OpenID Connect sign-in (docs/design/10-security.md section 12, decision D28 item 6).

-- Whom a provider's subject signs in as: the temporal platform entity SecUserIdentity, linked by administrators.
CREATE TABLE sec_user_identity_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_identity_id  uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    user_id           uuid          NOT NULL REFERENCES entity_registry (entity_id),
    provider          varchar(40)   NOT NULL,
    subject           varchar(255)  NOT NULL,
    CONSTRAINT sec_user_identity_version_uk UNIQUE (user_identity_id, version_no)
);
CREATE INDEX sec_user_identity_version_current_idx
    ON sec_user_identity_version (user_identity_id, effect_start_time DESC, version_no DESC);
CREATE INDEX sec_user_identity_version_process_idx ON sec_user_identity_version (process_seq_id);
CREATE INDEX sec_user_identity_version_subject_idx ON sec_user_identity_version (provider, subject);
SELECT jabiz_protect_append_only('sec_user_identity_version');

-- Pending authorization requests: state, nonce and browser binder as SHA-256, the PKCE verifier (useless without the
-- code). Using a state inserts its use, whose primary key allows it once; nothing is updated. Anyone may start a
-- sign-in, so these are not kept: expired requests are deleted (not append-only; not sealed).
CREATE TABLE sec_oidc_state (
    state_hash    char(64)     PRIMARY KEY,
    provider_id   varchar(40)  NOT NULL,
    nonce_hash    char(64)     NOT NULL,
    binder_hash   char(64)     NOT NULL,
    code_verifier varchar(128) NOT NULL,
    created_at    timestamptz  NOT NULL,
    expires_at    timestamptz  NOT NULL
);

CREATE TABLE sec_oidc_state_use (
    state_hash char(64)    PRIMARY KEY REFERENCES sec_oidc_state ON DELETE CASCADE,
    used_at    timestamptz NOT NULL
);
CREATE INDEX sec_oidc_state_expires_idx ON sec_oidc_state (expires_at);

-- The provider account each session came through: at every refresh its link must still exist.
ALTER TABLE sec_refresh_token ADD COLUMN identity_id uuid;
