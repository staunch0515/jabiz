-- Values bound as the type of their field (ROADMAP 16b fix): marks on users, by a text column and by a uuid column
-- that refers to the platform user, read by a sign-in guard and through datasets scoped to the caller.
CREATE TABLE it_account_mark (
    f_id        varchar(64) PRIMARY KEY,
    f_user_text varchar(64),
    f_user_ref  uuid,
    f_reason    text,
    f_version   bigint      NOT NULL DEFAULT 1
);
