-- Sign-in with tokens another platform signs (decision D59; docs/design/10-security.md section 15.12): each token id
-- is used once by a sign-in. Anyone may present a token, so the rows are kept only until the token expires (each use
-- removes the expired ones); an ordinary table, as the OpenID Connect states.
CREATE TABLE sec_external_token_use (
    provider    varchar(36)  NOT NULL,
    token_id    varchar(255) NOT NULL,
    used_at     timestamptz  NOT NULL,
    expires_at  timestamptz  NOT NULL,
    PRIMARY KEY (provider, token_id)
);
CREATE INDEX sec_external_token_use_expires_idx ON sec_external_token_use (expires_at);
