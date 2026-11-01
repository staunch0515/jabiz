-- Q69 (docs/quizbuks/plans/Q69-certifications.md): certifications, what passing certain publications is used for.
-- The rounds (which publication counts for which certification), each person's records (passes, grants and
-- revocations, only ever inserted) and the official publishers. Ordinary tables written by the processes only.

CREATE TABLE qb_certification_round (
    round_id              varchar(36)   PRIMARY KEY,
    certification         varchar(40)   NOT NULL,
    certification_version numeric(4, 0) NOT NULL,
    publication_id        varchar(36)   NOT NULL,
    owner                 varchar(128)  NOT NULL,
    added_by              varchar(128)  NOT NULL,
    added_at              timestamptz   NOT NULL,
    closed_at             timestamptz,
    version               bigint        NOT NULL,
    CONSTRAINT uk_qb_certification_round_publication UNIQUE (publication_id)
);
CREATE INDEX qb_certification_round_certification_idx ON qb_certification_round (certification, certification_version);

CREATE TABLE qb_certification_record (
    record_id             varchar(36)   PRIMARY KEY,
    user_id               varchar(128)  NOT NULL,
    certification         varchar(40)   NOT NULL,
    certification_version numeric(4, 0) NOT NULL,
    kind                  varchar(10)   NOT NULL,
    attempt_id            varchar(36),
    round_id              varchar(36),
    reason                varchar(500),
    actor                 varchar(128)  NOT NULL,
    owner                 varchar(128)  NOT NULL,
    at                    timestamptz   NOT NULL,
    valid_until           timestamptz,
    version               bigint        NOT NULL,
    CONSTRAINT uk_qb_certification_record_attempt UNIQUE (attempt_id)
);
CREATE INDEX qb_certification_record_user_idx ON qb_certification_record (user_id, certification);
CREATE INDEX qb_certification_record_owner_idx ON qb_certification_record (owner, at);

CREATE TABLE qb_official_publisher (
    official_id varchar(36)   PRIMARY KEY,
    user_id     varchar(128)  NOT NULL,
    owner       varchar(128)  NOT NULL,
    active      boolean       NOT NULL,
    set_by      varchar(128)  NOT NULL,
    set_at      timestamptz   NOT NULL,
    version     bigint        NOT NULL,
    CONSTRAINT uk_qb_official_publisher_user UNIQUE (user_id)
);
