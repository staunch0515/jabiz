-- Second factor (docs/design/10-security.md sections 9–11, decision D28).

-- A user's TOTP secret (AES-GCM, bound to the user) and the hashes of the unused recovery codes: the temporal
-- platform entity SecUserMfa, written only by the enrolment and reset processes (decision D5: versions are inserted).
CREATE TABLE sec_user_mfa_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_mfa_id       uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    user_id           uuid          NOT NULL REFERENCES entity_registry (entity_id),
    secret            varchar(200)  NOT NULL,
    confirmed         boolean       NOT NULL,
    confirmed_time    timestamptz,
    confirmed_step    bigint,
    recovery_codes    varchar(1000),
    CONSTRAINT sec_user_mfa_version_uk UNIQUE (user_mfa_id, version_no)
);
CREATE INDEX sec_user_mfa_version_current_idx
    ON sec_user_mfa_version (user_mfa_id, effect_start_time DESC, version_no DESC);
CREATE INDEX sec_user_mfa_version_process_idx ON sec_user_mfa_version (process_seq_id);
CREATE INDEX sec_user_mfa_version_user_idx ON sec_user_mfa_version (user_id);
SELECT jabiz_protect_append_only('sec_user_mfa_version');

-- Roles whose holders must have passed a second factor in their session.
ALTER TABLE sec_role_version ADD COLUMN require_mfa boolean;

-- What each attempt was checked with, and the last TOTP step accepted so far (carried from record to record).
ALTER TABLE sec_login_record_version ADD COLUMN factor varchar(20);
ALTER TABLE sec_login_record_version ADD COLUMN mfa_step bigint;

-- When the sign-in of a session passed a second factor; each refresh carries it into the next access token.
ALTER TABLE sec_refresh_token ADD COLUMN mfa_at timestamptz;
