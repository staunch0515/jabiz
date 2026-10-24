-- Collecting a prize at the sponsor's place (docs/quizbuks/plans/Q22-reward-methods.md, Q22b). The sponsor's console
-- shows a code the taker scans: a rotating one (a token good for two minutes, issued again every minute) completes the
-- redemption at once; a printed one (a lasting token on a poster or a counter card) makes a claim the sponsor confirms
-- within ten minutes, since the taker could scan it anywhere. The tokens are an ordinary table written by the
-- processes only; the redemption's versions record the claim, who confirmed it and when.
CREATE TABLE qb_redeem_token (
    token_id   varchar(36)  PRIMARY KEY,
    sponsor_id varchar(128) NOT NULL,
    token_value varchar(64) NOT NULL,
    kind       varchar(10)  NOT NULL CHECK (kind IN ('ROTATING', 'PRINTED')),
    issued_at  timestamptz  NOT NULL,
    expires_at timestamptz,
    revoked_at timestamptz,
    version    bigint       NOT NULL,
    CONSTRAINT uk_qb_redeem_token UNIQUE (token_value)
);
CREATE INDEX qb_redeem_token_sponsor_idx ON qb_redeem_token (sponsor_id, kind);

-- A claim from a printed code waits for the sponsor (CLAIMED); the kind of code, the claim's time and limit, who
-- confirmed it (the sponsor's user, or the sponsor's own code), and an administrator's void with its note.
ALTER TABLE qb_redemption_version DROP CONSTRAINT qb_redemption_version_status_check;
ALTER TABLE qb_redemption_version ADD CONSTRAINT qb_redemption_version_status_check
    CHECK (status IN ('DUE', 'CLAIMED', 'REDEEMED', 'EXPIRED', 'VOID'));
ALTER TABLE qb_redemption_version
    ADD COLUMN claim_kind  varchar(10) CHECK (claim_kind IN ('ROTATING', 'PRINTED')),
    ADD COLUMN claimed_at  timestamptz,
    ADD COLUMN confirm_by  timestamptz,
    ADD COLUMN redeemed_by varchar(128),
    ADD COLUMN voided_by   varchar(128),
    ADD COLUMN void_note   varchar(500);
-- The jobs' scans: the claims past their time to confirm (every ten minutes) and the prizes past their time to
-- collect (daily), among the open versions only.
CREATE INDEX qb_redemption_version_confirm_idx ON qb_redemption_version (confirm_by) WHERE status = 'CLAIMED';
CREATE INDEX qb_redemption_version_expires_idx ON qb_redemption_version (expires_at)
    WHERE status IN ('DUE', 'CLAIMED');
