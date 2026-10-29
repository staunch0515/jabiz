-- Operators (docs/quizbuks/plans/Q48-operators.md, Q48a): the agents who run sponsors in a back office of their own
-- (/operator/). Headquarters ("direct") is no row: a sponsor without an assignment is headquarters' own. All four
-- tables are ordinary, written by the processes only; their history is the platform's audit.

-- An operator: who it is, how its money is collected (PLATFORM: by the platform, settled monthly, D-Q48-8) and
-- whether it runs (SUSPENDED: its members do not sign in).
CREATE TABLE qb_operator (
    operator_id   varchar(36)    PRIMARY KEY,
    name          varchar(200)   NOT NULL,
    contact_name  varchar(100)   NOT NULL,
    contact_email varchar(254)   NOT NULL,
    collection    varchar(20)    NOT NULL,
    status        varchar(20)    NOT NULL,
    note          varchar(2000),
    created_at    timestamptz    NOT NULL,
    created_by    varchar(128)   NOT NULL,
    revision      numeric(9, 0)  NOT NULL,
    version       bigint         NOT NULL
);

-- A member of an operator's staff: headquarters names them (their role QB_OPERATOR is a protected grant, given
-- through the platform's four eyes), the operator's lead or headquarters removes them. A removed member's row stays
-- (removed_at), so the spot checks know who decided what; a person is a member of one operator at a time.
-- operator_active follows the operator's status, so the sign-in guard reads the member's own row only (by the
-- user's id, bound as a UUID: hence the column's type).
CREATE TABLE qb_operator_member (
    member_id       varchar(36)   PRIMARY KEY,
    operator_id     varchar(36)   NOT NULL REFERENCES qb_operator (operator_id),
    user_id         uuid          NOT NULL,
    lead            boolean       NOT NULL,
    operator_active boolean       NOT NULL,
    added_at        timestamptz   NOT NULL,
    added_by        varchar(128)  NOT NULL,
    removed_at      timestamptz,
    removed_by      varchar(128),
    version         bigint        NOT NULL
);
CREATE UNIQUE INDEX uk_qb_operator_member_user ON qb_operator_member (user_id) WHERE removed_at IS NULL;
CREATE INDEX qb_operator_member_operator_idx ON qb_operator_member (operator_id);
CREATE INDEX qb_operator_member_user_idx ON qb_operator_member (user_id);

-- Which operator a sponsor belongs to (D-Q48-1); no row: headquarters. Reassigning changes the row (the audit keeps
-- the history).
CREATE TABLE qb_sponsor_operator (
    assignment_id varchar(36)    PRIMARY KEY,
    user_id       varchar(128)   NOT NULL,
    operator_id   varchar(36)    NOT NULL REFERENCES qb_operator (operator_id),
    assigned_at   timestamptz    NOT NULL,
    assigned_by   varchar(128)   NOT NULL,
    reason        varchar(500)   NOT NULL,
    version       bigint         NOT NULL,
    CONSTRAINT uk_qb_sponsor_operator_user UNIQUE (user_id)
);
CREATE INDEX qb_sponsor_operator_operator_idx ON qb_sponsor_operator (operator_id);

-- Headquarters' spot check of a review an operator decided (D-Q48-5): one per decision, changed by checking again.
CREATE TABLE qb_review_spot_check (
    check_id    varchar(36)    PRIMARY KEY,
    decision_id varchar(36)    NOT NULL,
    result      varchar(20)    NOT NULL,
    note        varchar(2000),
    checked_at  timestamptz    NOT NULL,
    checked_by  varchar(128)   NOT NULL,
    version     bigint         NOT NULL,
    CONSTRAINT uk_qb_review_spot_check_decision UNIQUE (decision_id)
);

-- Which operator took a publication down (paused or ended it as an administrator); null: headquarters, or nobody.
-- An operator resumes only its own (D-Q48-5: headquarters' takedowns come first).
ALTER TABLE qb_publication_version ADD COLUMN takedown_operator_id varchar(36);

-- Which operator banned a user; null: headquarters. An operator lifts only its own bans.
ALTER TABLE qb_user_ban_version ADD COLUMN operator_id varchar(36);
