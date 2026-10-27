-- QuizBuks for Shopify, S1 (docs/quizbuks/shopify/S1-install.md): the shops that installed the app, their staff, their
-- Shopify access tokens and the privacy requests Shopify sent. This module's migrations share the application's version
-- sequence and history (a version taken twice fails at startup). Ordinary tables written by the module's processes only;
-- a shop's rows are deleted when Shopify asks to erase its data (shop/redact).

CREATE TABLE qbs_shop (
    shop_id        varchar(36)   PRIMARY KEY,
    shop_domain    varchar(255)  NOT NULL,
    status         varchar(11)   NOT NULL CHECK (status IN ('ACTIVE', 'UNINSTALLED')),
    installed_at   timestamptz   NOT NULL,
    uninstalled_at timestamptz,
    shop_name      varchar(255),
    connection     varchar(9)    NOT NULL CHECK (connection IN ('NONE', 'CONNECTED', 'RECONNECT')),
    version        bigint        NOT NULL,
    CONSTRAINT uk_qbs_shop_domain UNIQUE (shop_domain)
);

-- A user is a member of one shop; each shop has exactly one ADMIN (the index stops a second, the processes keep one).
CREATE TABLE qbs_shop_member (
    member_id   varchar(36)   PRIMARY KEY,
    shop_id     varchar(36)   NOT NULL,
    shop_domain varchar(255)  NOT NULL,
    user_id     varchar(128)  NOT NULL,
    staff_id    varchar(255)  NOT NULL,
    role        varchar(6)    NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    shop_status varchar(11)   NOT NULL CHECK (shop_status IN ('ACTIVE', 'UNINSTALLED')),
    joined_at   timestamptz   NOT NULL,
    version     bigint        NOT NULL,
    CONSTRAINT uk_qbs_shop_member_user UNIQUE (user_id)
);
CREATE INDEX qbs_shop_member_shop_idx ON qbs_shop_member (shop_id);
CREATE UNIQUE INDEX qbs_shop_member_admin_idx ON qbs_shop_member (shop_id) WHERE role = 'ADMIN';

CREATE TABLE qbs_shop_token (
    shop_id              varchar(36)   PRIMARY KEY,
    sealed_access_token  text          NOT NULL,
    access_expires_at    timestamptz   NOT NULL,
    sealed_refresh_token text          NOT NULL,
    refresh_expires_at   timestamptz   NOT NULL,
    scope                varchar(1000),
    obtained_at          timestamptz   NOT NULL,
    version              bigint        NOT NULL
);

CREATE TABLE qbs_compliance_request (
    request_id       varchar(36)   PRIMARY KEY,
    inbound_event_id varchar(36)   NOT NULL,
    topic            varchar(22)   NOT NULL
        CHECK (topic IN ('customers/data_request', 'customers/redact', 'shop/redact')),
    shop_domain      varchar(255),
    outcome          varchar(16)   NOT NULL
        CHECK (outcome IN ('NO_DATA', 'DELETED', 'KEPT_REINSTALLED', 'NOT_FOUND', 'MISMATCH')),
    received_at      timestamptz   NOT NULL,
    handled_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL,
    CONSTRAINT uk_qbs_compliance_event UNIQUE (inbound_event_id)
);
CREATE INDEX qbs_compliance_request_shop_idx ON qbs_compliance_request (shop_domain, received_at);
