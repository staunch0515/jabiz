-- Key rotation (decision D78; ROADMAP 17zw).

-- A second factor an approved "everyone sets up a new second factor" left to be set up again: until the user confirms a
-- new one, the sign-in asks for it (MFA_ENROLLMENT_REQUIRED). Only added (decision D58).
ALTER TABLE sec_user_mfa_version ADD COLUMN reenroll_required boolean NOT NULL DEFAULT false;

-- The requests of that one-time action, and their approval by another administrator. Only inserted.
CREATE TABLE sec_mfa_reenrollment (
    request_id     uuid          PRIMARY KEY,
    requested_by   varchar(100)  NOT NULL,
    requested_time timestamptz   NOT NULL,
    reason         varchar(1000) NOT NULL,
    process_seq_id bigint        NOT NULL REFERENCES op_process (process_seq_id)
);
CREATE INDEX sec_mfa_reenrollment_time_idx ON sec_mfa_reenrollment (requested_time DESC);
SELECT jabiz_protect_append_only('sec_mfa_reenrollment');

CREATE TABLE sec_mfa_reenrollment_approval (
    request_id     uuid          PRIMARY KEY REFERENCES sec_mfa_reenrollment (request_id),
    approved_by    varchar(100)  NOT NULL,
    approved_time  timestamptz   NOT NULL,
    process_seq_id bigint        NOT NULL REFERENCES op_process (process_seq_id)
);
CREATE INDEX sec_mfa_reenrollment_approval_time_idx ON sec_mfa_reenrollment_approval (approved_time DESC);
SELECT jabiz_protect_append_only('sec_mfa_reenrollment_approval');

-- When the platform first saw a retired key (by its id, never the key): a retired JWT secret is accepted for
-- jabiz.security.jwt.previous-retention from then on. Only inserted.
CREATE TABLE sec_key_first_seen (
    key_kind   varchar(20)  NOT NULL,
    key_id     varchar(16)  NOT NULL,
    first_seen timestamptz  NOT NULL,
    PRIMARY KEY (key_kind, key_id)
);
SELECT jabiz_protect_append_only('sec_key_first_seen');
