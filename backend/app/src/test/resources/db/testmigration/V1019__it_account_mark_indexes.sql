-- Decision D80: a guard's load is read at every sign-in, so its compared column leads an index (GuardLoadIndexCheck).
-- V1018 is BulkWriteIT's table (17zz14).
CREATE INDEX it_account_mark_user_text_idx ON it_account_mark (f_user_text);
CREATE INDEX it_account_mark_user_ref_idx ON it_account_mark (f_user_ref);
