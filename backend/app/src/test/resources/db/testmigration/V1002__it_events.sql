-- Fixtures of the event and job integration tests (ROADMAP phase 9).
CREATE TABLE it_memo (
    f_id      varchar(64) PRIMARY KEY,
    f_text    text        NOT NULL,
    f_version bigint      NOT NULL DEFAULT 1
);

CREATE TABLE it_event_log (
    f_id       varchar(200) PRIMARY KEY,
    f_source   varchar(200) NOT NULL,
    f_consumer varchar(100) NOT NULL,
    f_version  bigint       NOT NULL DEFAULT 1
);
