-- Q48e-2 (docs/quizbuks/plans/Q48e-operator-isolation.md): the operators' plans, accounts and withdrawals. The money
-- stays with the platform: a month's settlement credits what is payable to the operator's account (CREDITED), and the
-- operator withdraws from it; settlements issued before keep their own payment (ISSUED, PAYING, PAID).

-- The plan a month was settled on and its fixed fee (0 before plans existed, and while the operator is suspended).
ALTER TABLE qb_operator_settlement ADD COLUMN plan varchar(2);
ALTER TABLE qb_operator_settlement ADD COLUMN fixed_fee numeric(18, 0) NOT NULL DEFAULT 0 CHECK (fixed_fee >= 0);
ALTER TABLE qb_operator_settlement DROP CONSTRAINT qb_operator_settlement_status_check;
ALTER TABLE qb_operator_settlement ADD CONSTRAINT qb_operator_settlement_status_check
    CHECK (status IN ('ISSUED', 'PAYING', 'PAID', 'CREDITED'));

-- A change of an operator's plan from a month on: proposed by headquarters, approved by another administrator.
CREATE TABLE qb_operator_plan (
    plan_change_id      varchar(36)   PRIMARY KEY,
    operator_id         varchar(36)   NOT NULL REFERENCES qb_operator (operator_id),
    plan                varchar(2)    NOT NULL CHECK (plan IN ('1', '2', '3', '4', '5')),
    effective_month     varchar(7)    NOT NULL CHECK (effective_month ~ '^[0-9]{4}-[0-9]{2}$'),
    reason              varchar(500)  NOT NULL,
    status              varchar(10)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    proposed_by         varchar(128)  NOT NULL,
    proposed_at         timestamptz   NOT NULL,
    approval_request_id varchar(36),
    decided_at          timestamptz,
    version             bigint        NOT NULL
);
CREATE INDEX qb_operator_plan_operator_idx ON qb_operator_plan (operator_id, effective_month);
-- One change waits for its approval at a time.
CREATE UNIQUE INDEX qb_operator_plan_pending_idx ON qb_operator_plan (operator_id) WHERE status = 'PENDING';

-- The bank account an operator's withdrawals are paid to: one an operator.
CREATE TABLE qb_operator_bank_account (
    bank_account_id varchar(36)  PRIMARY KEY,
    operator_id     varchar(36)  NOT NULL REFERENCES qb_operator (operator_id),
    bank_code       char(4)      NOT NULL CHECK (bank_code ~ '^[0-9]{4}$'),
    bank_name       varchar(60)  NOT NULL,
    branch_code     char(3)      NOT NULL CHECK (branch_code ~ '^[0-9]{3}$'),
    branch_name     varchar(60)  NOT NULL,
    account_type    varchar(10)  NOT NULL CHECK (account_type IN ('ORDINARY', 'CURRENT', 'SAVINGS')),
    account_number  char(7)      NOT NULL CHECK (account_number ~ '^[0-9]{7}$'),
    account_holder  varchar(48)  NOT NULL,
    changed_at      timestamptz  NOT NULL,
    changed_by      varchar(128) NOT NULL,
    version         bigint       NOT NULL,
    CONSTRAINT uk_qb_operator_bank_account_operator UNIQUE (operator_id)
);

-- A withdrawal from an operator's account, with the bank account as it was when asked for.
CREATE TABLE qb_operator_withdrawal (
    withdrawal_id          varchar(36)     PRIMARY KEY,
    operator_id            varchar(36)     NOT NULL REFERENCES qb_operator (operator_id),
    amount                 numeric(18, 0)  NOT NULL CHECK (amount > 0),
    status                 varchar(10)     NOT NULL
        CHECK (status IN ('REQUESTED', 'PAYING', 'PAID', 'REJECTED', 'CANCELLED')),
    requested_by           varchar(128)    NOT NULL,
    requested_at           timestamptz     NOT NULL,
    bank_code              char(4),
    bank_name              varchar(60),
    branch_code            char(3),
    branch_name            varchar(60),
    account_type           varchar(10),
    account_number         char(7),
    account_holder         varchar(48),
    paid_on                date,
    bank_reference         varchar(100),
    payment_recorded_by    varchar(128),
    payment_recorded_at    timestamptz,
    approval_request_id    varchar(36),
    payment_transaction_id varchar(36),
    paid_at                timestamptz,
    reject_reason          varchar(500),
    closed_at              timestamptz,
    revision               numeric(9, 0)   NOT NULL,
    version                bigint          NOT NULL
);
CREATE INDEX qb_operator_withdrawal_operator_idx ON qb_operator_withdrawal (operator_id, requested_at);
CREATE INDEX qb_operator_withdrawal_status_idx ON qb_operator_withdrawal (status, requested_at);
-- One open withdrawal an operator: the balance is checked under the operator's lock, the database backs it.
CREATE UNIQUE INDEX qb_operator_withdrawal_open_idx ON qb_operator_withdrawal (operator_id)
    WHERE status IN ('REQUESTED', 'PAYING');
