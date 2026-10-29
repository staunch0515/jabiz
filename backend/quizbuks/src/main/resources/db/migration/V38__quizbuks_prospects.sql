-- AI prospecting (docs/quizbuks/plans/Q48-operators.md, Q48c): an operator (or headquarters, owner 'direct') finds
-- businesses on the web, Claude researches each and drafts a trial quiz and a mail, a person approves it, and it goes
-- out within the day's cap through the owner's own mail server. Every table is ordinary, written by the processes
-- only; the history is the platform's audit.

-- An owner's settings: whether it prospects, how many mails a day, the score a business needs, the month's cost cap,
-- and who the mails say they are from (the law on commercial mail wants the sender's name, address and contact).
CREATE TABLE qb_prospect_setting (
    setting_id       varchar(36)    PRIMARY KEY,
    owner            varchar(36)    NOT NULL,
    enabled          boolean        NOT NULL,
    daily_cap        numeric(4, 0)  NOT NULL,
    min_score        numeric(3, 0)  NOT NULL,
    monthly_cost_cap numeric(10, 2) NOT NULL,
    sender_name      varchar(100)   NOT NULL,
    sender_address   varchar(300)   NOT NULL,
    sender_contact   varchar(254)   NOT NULL,
    updated_at       timestamptz    NOT NULL,
    updated_by       varchar(128)   NOT NULL,
    revision         numeric(9, 0)  NOT NULL,
    version          bigint         NOT NULL,
    CONSTRAINT uk_qb_prospect_setting_owner UNIQUE (owner)
);

-- A direction of search: what kind of business to look for, where, in which language.
CREATE TABLE qb_prospect_direction (
    direction_id varchar(36)    PRIMARY KEY,
    owner        varchar(36)    NOT NULL,
    name         varchar(100)   NOT NULL,
    brief        varchar(1000)  NOT NULL,
    country      varchar(2),
    language     varchar(5),
    active       boolean        NOT NULL,
    per_search   numeric(3, 0)  NOT NULL,
    last_search_at timestamptz,
    created_at   timestamptz    NOT NULL,
    created_by   varchar(128)   NOT NULL,
    revision     numeric(9, 0)  NOT NULL,
    version      bigint         NOT NULL
);
CREATE INDEX qb_prospect_direction_owner_idx ON qb_prospect_direction (owner);

-- A business found: one per owner and domain. The research (what it sells, its public business address, the score
-- and its reasons), the trial quiz and the mail are kept as JSON text; trial_key opens the trial page and marks
-- the sign-ups it brings (Q48d).
CREATE TABLE qb_prospect (
    prospect_id       varchar(36)    PRIMARY KEY,
    owner             varchar(36)    NOT NULL,
    direction_id      varchar(36)    NOT NULL REFERENCES qb_prospect_direction (direction_id),
    domain            varchar(253)   NOT NULL,
    website_url       varchar(500)   NOT NULL,
    company           varchar(200),
    status            varchar(20)    NOT NULL,
    found_at          timestamptz    NOT NULL,
    research_job_id   varchar(36),
    research_started_at timestamptz,
    researched_at     timestamptz,
    language          varchar(5),
    country           varchar(2),
    sells_online      boolean,
    shopify           boolean,
    public_email      varchar(254),
    refuses_marketing boolean,
    score             numeric(3, 0),
    score_reasons     varchar(4000),
    products          varchar(8000),
    trial             varchar(20000),
    mail_subject      varchar(200),
    mail_body         varchar(4000),
    failure           varchar(500),
    trial_key      varchar(64),
    approved_at       timestamptz,
    approved_by       varchar(128),
    sent_at           timestamptz,
    discarded_at      timestamptz,
    discarded_by      varchar(128),
    revision          numeric(9, 0)  NOT NULL,
    version           bigint         NOT NULL,
    CONSTRAINT uk_qb_prospect_domain UNIQUE (owner, domain),
    CONSTRAINT uk_qb_prospect_trial_key UNIQUE (trial_key)
);
CREATE INDEX qb_prospect_owner_status_idx ON qb_prospect (owner, status, found_at);
CREATE INDEX qb_prospect_status_idx ON qb_prospect (status, approved_at);
CREATE INDEX qb_prospect_email_idx ON qb_prospect (lower(public_email));

-- Never write to these again (D-Q48 §3.3): an address or a whole domain, whoever recorded it, for every owner.
CREATE TABLE qb_prospect_block (
    block_id   varchar(36)    PRIMARY KEY,
    kind       varchar(10)    NOT NULL,
    value      varchar(254)   NOT NULL,
    reason     varchar(20)    NOT NULL,
    owner      varchar(36)    NOT NULL,
    note       varchar(500),
    created_at timestamptz    NOT NULL,
    created_by varchar(128)   NOT NULL,
    version    bigint         NOT NULL,
    CONSTRAINT uk_qb_prospect_block UNIQUE (kind, value)
);

-- What prospecting cost an owner in a month (Japan's calendar month): tokens, searches and the dollars they come to,
-- for the month's cap and the operator's settlement (Q48d).
CREATE TABLE qb_prospect_usage (
    usage_id      varchar(36)     PRIMARY KEY,
    owner         varchar(36)     NOT NULL,
    month         varchar(7)      NOT NULL,
    input_tokens  numeric(14, 0)  NOT NULL,
    output_tokens numeric(14, 0)  NOT NULL,
    searches      numeric(9, 0)   NOT NULL,
    jobs          numeric(9, 0)   NOT NULL,
    cost_usd      numeric(12, 4)  NOT NULL,
    version       bigint          NOT NULL,
    CONSTRAINT uk_qb_prospect_usage UNIQUE (owner, month)
);
