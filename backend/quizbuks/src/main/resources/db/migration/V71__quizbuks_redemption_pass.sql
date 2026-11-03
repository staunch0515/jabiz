-- Q89c (docs/quizbuks/plans/Q89-sponsored-benefits.md, D-Q89-10): the taker shows a pass the counter scans (or reads
-- six digits of): one row per prize, replaced when the taker asks again, good for 60 seconds and used once. Only the
-- hashes of the QR's key and of the digits (with the hander's id) are kept. An ordinary table written by the
-- processes only.
CREATE TABLE qb_redemption_pass (
    pass_id       varchar(36)  PRIMARY KEY,
    redemption_id varchar(36)  NOT NULL,
    user_id       varchar(128) NOT NULL,
    handler_id    varchar(128) NOT NULL,
    pass_hash     varchar(64)  NOT NULL,
    digits_hash   varchar(64)  NOT NULL,
    issued_at     timestamptz  NOT NULL,
    expires_at    timestamptz  NOT NULL,
    used_at       timestamptz,
    used_by       varchar(128),
    version       bigint       NOT NULL,
    CONSTRAINT uk_qb_redemption_pass_redemption UNIQUE (redemption_id),
    CONSTRAINT uk_qb_redemption_pass_hash UNIQUE (pass_hash)
);
-- A counter's lookup by the digits: its passes not used yet.
CREATE INDEX qb_redemption_pass_digits_idx ON qb_redemption_pass (handler_id, digits_hash) WHERE used_at IS NULL;
CREATE INDEX qb_redemption_pass_user_idx ON qb_redemption_pass (user_id);

-- A prize collected by the taker's pass records the kind PASS.
ALTER TABLE qb_redemption_version DROP CONSTRAINT qb_redemption_version_claim_kind_check;
ALTER TABLE qb_redemption_version ADD CONSTRAINT qb_redemption_version_claim_kind_check
    CHECK (claim_kind IN ('ROTATING', 'PRINTED', 'PASS'));
