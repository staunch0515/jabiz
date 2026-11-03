-- Q47c (docs/quizbuks/plans/Q47c-audience-billing.md): the audience rules as managed records, and each owner's price
-- list (an operator's, or headquarters' "direct"). Ordinary tables written by the processes only; a rule or an owner
-- without a row has the default (the rule open and billable, no price list), and the first save creates the row. A
-- price's history is in the audit records.
CREATE TABLE qb_audience_rule (
    rule_key    varchar(32)   PRIMARY KEY,
    enabled     boolean       NOT NULL,
    billable    boolean       NOT NULL,
    sort_order  integer       NOT NULL,
    updated_at  timestamptz   NOT NULL,
    updated_by  varchar(128)  NOT NULL,
    version     bigint        NOT NULL
);

CREATE TABLE qb_audience_price_list (
    price_list_id  varchar(36)    PRIMARY KEY,
    owner          varchar(64)    NOT NULL UNIQUE,
    minimum_fee    numeric(12, 0) NOT NULL CHECK (minimum_fee >= 0),
    reason         varchar(500)   NOT NULL,
    updated_at     timestamptz    NOT NULL,
    updated_by     varchar(128)   NOT NULL,
    version        bigint         NOT NULL
);

CREATE TABLE qb_audience_price (
    price_id       varchar(36)    PRIMARY KEY,
    price_list_id  varchar(36)    NOT NULL REFERENCES qb_audience_price_list (price_list_id),
    rule_key       varchar(32)    NOT NULL,
    daily_price    numeric(12, 0) NOT NULL CHECK (daily_price >= 0),
    version        bigint         NOT NULL,
    CONSTRAINT qb_audience_price_one UNIQUE (price_list_id, rule_key)
);
