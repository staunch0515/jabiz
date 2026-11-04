-- Passkeys (decision D71; docs/design/10-security.md section 15.16).

-- A passkey a user registered: the credential's id as the authenticator gives it (Base64url), its public key with the
-- authenticator's data as WebAuthn encodes them (COSE, Base64url), the signature counter last seen, and what the user
-- calls it. An ordinary table, written by the platform's processes only: a passkey is renamed, used (counter and last
-- use) and removed. The credential's id is unique across users: one passkey belongs to one account.
CREATE TABLE sec_user_passkey (
    passkey_id      varchar(36)   PRIMARY KEY,
    user_id         uuid          NOT NULL REFERENCES entity_registry (entity_id),
    credential_id   varchar(1400) NOT NULL,
    credential_data varchar(4000) NOT NULL,
    sign_count      bigint        NOT NULL,
    name            varchar(100)  NOT NULL,
    transports      varchar(200),
    backed_up       boolean       NOT NULL,
    aaguid          varchar(36),
    created_at      timestamptz   NOT NULL,
    last_used_at    timestamptz,
    version         bigint        NOT NULL,
    CONSTRAINT uk_sec_user_passkey_credential UNIQUE (credential_id)
);
CREATE INDEX sec_user_passkey_user_idx ON sec_user_passkey (user_id);

-- A challenge handed out for a passkey ceremony, kept as its SHA-256 until it is answered (the row is taken out then)
-- or expires (each new challenge removes the expired ones). What it is for, which entry and which user (none for a
-- sign-in: the passkey names its user) are checked when it is answered.
CREATE TABLE sec_passkey_challenge (
    challenge_hash varchar(64)  PRIMARY KEY,
    purpose        varchar(20)  NOT NULL CHECK (purpose IN ('SIGN_IN', 'SECOND_FACTOR', 'STEP_UP', 'REGISTER')),
    entry          varchar(40)  NOT NULL,
    user_id        uuid,
    created_at     timestamptz  NOT NULL,
    expires_at     timestamptz  NOT NULL
);
CREATE INDEX sec_passkey_challenge_expiry_idx ON sec_passkey_challenge (expires_at);
CREATE INDEX sec_passkey_challenge_user_idx ON sec_passkey_challenge (user_id);
