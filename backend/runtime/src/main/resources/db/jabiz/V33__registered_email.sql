-- The address a user registered themselves with (docs/design/10-security.md section 15.7; decision D36): written by
-- self-registration only. Account pre-claim protection, and mailing a password reset or a new verification to an
-- address nobody has proven yet, are only for a self-registered user still on this address and never verified; an
-- address an administrator set is never trusted for them. Users created before have none (column only, no UPDATE).
ALTER TABLE sec_user_version ADD COLUMN registered_email varchar(320);
