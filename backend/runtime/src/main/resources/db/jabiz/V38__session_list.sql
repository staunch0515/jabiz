-- The session list (docs/design/10-security.md section 15.9; ROADMAP phase 16g). Columns and an index only: no new
-- tables, the tables stay append-only, and rows sealed before keep the digest of the columns they were sealed with.

-- Where each token was issued (signing in or refreshing): the client's address and user agent, cleaned and cut like
-- those of the login records (section 15.5). The newest token of a family tells where its session was last used.
ALTER TABLE sec_refresh_token ADD COLUMN client_ip varchar(45);
ALTER TABLE sec_refresh_token ADD COLUMN user_agent varchar(256);

-- A user's live sessions: their tokens not yet expired (the list, and ending every session of a user).
CREATE INDEX sec_refresh_token_user_idx ON sec_refresh_token (user_id, expires_at);

-- The operation that ended a session, when a process did: the user's own revocation, an administrator's, a new
-- password. Null for the others (signing out, reuse, a guard's refusal at a refresh).
ALTER TABLE sec_refresh_family_revocation ADD COLUMN process_seq_id bigint REFERENCES op_process (process_seq_id);
