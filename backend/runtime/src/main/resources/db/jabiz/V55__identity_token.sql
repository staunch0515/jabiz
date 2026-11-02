-- The tokens that revoke a user's authorization at a provider when its link goes (decision D67 item 5): Apple's
-- refresh token of each sign-in or link, sealed with JABIZ_SEAL_KEY for its link. An ordinary table: a token is
-- replaced at each sign-in and deleted once revoked. identity_id is the link's key without a foreign key: the token
-- outlives its link until revoked. subject_hash tells the provider's accounts apart (SHA-256, not the subject): a newer
-- link of the same account replaces an older one's token.
CREATE TABLE sec_identity_token (
    identity_id     uuid          PRIMARY KEY,
    provider        varchar(40)   NOT NULL,
    subject_hash    char(64)      NOT NULL,
    sealed_token    varchar(4000) NOT NULL,
    updated_at      timestamptz   NOT NULL,
    revoke_attempts numeric(9, 0) NOT NULL,
    next_attempt_at timestamptz,
    version         bigint        NOT NULL
);
CREATE INDEX sec_identity_token_subject_idx ON sec_identity_token (provider, subject_hash);
