-- Tables of the integration-test fixtures (see com.jabiz.it.fixture.ItFixtures).
-- One entity per dataset policy under test, so that the policies do not interfere.

CREATE TABLE it_ticket (
    f_id          varchar(64)   PRIMARY KEY,
    f_title       text          NOT NULL,
    f_amount      numeric(19,0),
    f_status      varchar(32),
    f_owner       varchar(64),
    f_created_at  timestamptz   NOT NULL,
    f_version     bigint        NOT NULL DEFAULT 1
);

CREATE TABLE it_soft (
    f_id        varchar(64) PRIMARY KEY,
    f_name      text,
    f_version   bigint      NOT NULL DEFAULT 1,
    is_deleted  boolean     NOT NULL DEFAULT false,
    deleted_at  timestamptz
);

CREATE TABLE it_regional (
    f_id       varchar(64) PRIMARY KEY,
    f_region   varchar(8)  NOT NULL,
    f_name     text,
    f_version  bigint      NOT NULL DEFAULT 1
);

CREATE TABLE it_readonly (
    f_id       varchar(64) PRIMARY KEY,
    f_name     text,
    f_version  bigint      NOT NULL DEFAULT 1
);

CREATE TABLE it_tenant (
    f_id       varchar(64) PRIMARY KEY,
    f_tenant   varchar(64) NOT NULL,
    f_name     text,
    f_version  bigint      NOT NULL DEFAULT 1
);

CREATE TABLE it_unique (
    f_id       varchar(64) PRIMARY KEY,
    f_code     varchar(32) NOT NULL,
    f_version  bigint      NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX uk_it_unique_code ON it_unique (f_code);
