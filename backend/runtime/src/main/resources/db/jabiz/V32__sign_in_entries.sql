-- Sign-in entries, verified e-mail addresses and where sign-ins come from (docs/design/10-security.md section 15;
-- decision D36). Columns and indexes only: no new tables.

-- A user's e-mail address is unique regardless of case once set (decision D36 item 3). The platform checks it like
-- every temporal uniqueness (decision D6) and finds its candidates through this index (decision D29). Addresses that
-- already collide must be resolved by an administrator first: the migration stops rather than pick one.
DO $$
DECLARE
    duplicates text;
BEGIN
    SELECT string_agg(address, ', ' ORDER BY address) INTO duplicates FROM (
        SELECT lower(email) AS address
        FROM (SELECT DISTINCT ON (user_id) user_id, email, is_deleted FROM sec_user_version
              ORDER BY user_id, effect_start_time DESC, version_no DESC) latest
        WHERE NOT is_deleted AND email IS NOT NULL
        GROUP BY lower(email) HAVING count(*) > 1) taken;
    IF duplicates IS NOT NULL THEN
        RAISE EXCEPTION 'Users share e-mail addresses (regardless of case): %. Give each user an address of their own (or none), then migrate again (decision D36).', duplicates;
    END IF;
END $$;

CREATE INDEX sec_user_version_email_idx ON sec_user_version (lower(email)) WHERE email IS NOT NULL;

-- The address a verification proved, and when (written by the verification processes only). The address counts as
-- verified while it equals the current one regardless of case, so changing the address ends its verification.
ALTER TABLE sec_user_version ADD COLUMN email_verified_at timestamptz;
ALTER TABLE sec_user_version ADD COLUMN verified_email varchar(320);

-- Where an attempt came from: the entry, and the client's address and user agent (decision D36 item 7).
ALTER TABLE sec_login_record_version ADD COLUMN entry varchar(40);
ALTER TABLE sec_login_record_version ADD COLUMN client_ip varchar(45);
ALTER TABLE sec_login_record_version ADD COLUMN user_agent varchar(256);

-- The entry a session (refresh token family) and a pending sign-in through an identity provider belong to. Rows from
-- before this migration have none and are read as the administration entry "admin".
ALTER TABLE sec_refresh_token ADD COLUMN entry varchar(40);
ALTER TABLE sec_oidc_state ADD COLUMN entry varchar(40);
