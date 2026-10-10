-- Sign-in entries and verified addresses (docs/design/10-security.md section 15): a dataset whose writers need a
-- verified e-mail address, and an ordinary entity whose code is unique regardless of case.
CREATE TABLE it_verified_note (
    f_id      varchar(64) PRIMARY KEY,
    f_text    text,
    f_version bigint      NOT NULL DEFAULT 1
);

CREATE TABLE it_unique_ci (
    f_id      varchar(64) PRIMARY KEY,
    f_code    varchar(32) NOT NULL,
    f_version bigint      NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX uk_it_unique_ci_code ON it_unique_ci (lower(f_code));
