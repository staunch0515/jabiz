-- Many rows per user for the loads of the notices before an account is deleted (ROADMAP 17zz22; see ItErasureFixtures).
CREATE TABLE it_erasure_point (
    f_id      varchar(64)   PRIMARY KEY,
    f_user    varchar(36),
    f_status  varchar(10),
    f_region  varchar(10),
    f_amount  numeric(19, 0),
    f_note    varchar(100),
    f_secret  varchar(100),
    f_card    varchar(30),
    f_version bigint        NOT NULL DEFAULT 1
);
CREATE INDEX it_erasure_point_user_idx ON it_erasure_point (f_user);
