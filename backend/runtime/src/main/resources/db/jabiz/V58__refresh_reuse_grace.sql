-- The reuse grace of refresh tokens (decision D73). A token now records the token whose use issued it, so that a
-- consumed token presented again within the grace can be checked against its successor (has it been used?). One
-- regrant per consumed token: the primary key of sec_refresh_token_regrant refuses a second (and a concurrent) one.
-- Both tables stay append-only (decision D12); the regrant records no user, address or agent (those are the
-- sibling token's, in sec_refresh_token).
ALTER TABLE sec_refresh_token ADD COLUMN parent_hash char(64);
CREATE INDEX sec_refresh_token_parent_idx ON sec_refresh_token (parent_hash) WHERE parent_hash IS NOT NULL;

CREATE TABLE sec_refresh_token_regrant (
    token_hash   char(64)    PRIMARY KEY REFERENCES sec_refresh_token,  -- the consumed token presented again
    family_id    uuid        NOT NULL,
    regranted_at timestamptz NOT NULL
);

SELECT jabiz_protect_append_only('sec_refresh_token_regrant');
