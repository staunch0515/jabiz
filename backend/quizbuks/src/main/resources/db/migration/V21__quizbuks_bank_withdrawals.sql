-- Withdrawals to a bank account (docs/quizbuks/plans/Q13-bank-transfer.md, Q13b): a taker keeps one Japanese bank
-- account and chooses how they are paid (payout_method: the daily Stripe transfer, or withdrawals they ask for to the
-- bank). A withdrawal is a transfer of method BANK: always reviewed, posted when approved (2120 -> 2130 and the fee),
-- then paid by hand by finance (2130 -> 1120, the reference of the bank's statement) or returned (the sending
-- reversed). The account as it was asked for is copied onto the transfer and never changes.
CREATE TABLE qb_bank_account (
    bank_account_id varchar(36)  PRIMARY KEY,
    user_id         varchar(128) NOT NULL,
    bank_code       char(4)      NOT NULL CHECK (bank_code ~ '^[0-9]{4}$'),
    bank_name       varchar(60)  NOT NULL,
    branch_code     char(3)      NOT NULL CHECK (branch_code ~ '^[0-9]{3}$'),
    branch_name     varchar(60)  NOT NULL,
    account_type    varchar(10)  NOT NULL CHECK (account_type IN ('ORDINARY', 'CURRENT', 'SAVINGS')),
    account_number  char(7)      NOT NULL CHECK (account_number ~ '^[0-9]{7}$'),
    account_holder  varchar(48)  NOT NULL,
    payout_method   varchar(10)  NOT NULL DEFAULT 'STRIPE' CHECK (payout_method IN ('STRIPE', 'BANK')),
    changed_at      timestamptz  NOT NULL,
    created_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL,
    CONSTRAINT uk_qb_bank_account_user UNIQUE (user_id),
    -- One bank account belongs to one user at most, whoever saves it.
    CONSTRAINT uk_qb_bank_account_number UNIQUE (bank_code, branch_code, account_type, account_number)
);

ALTER TABLE qb_transfer
    ADD COLUMN method          varchar(10)  NOT NULL DEFAULT 'STRIPE' CHECK (method IN ('STRIPE', 'BANK')),
    ADD COLUMN bank_code       char(4),
    ADD COLUMN bank_name       varchar(60),
    ADD COLUMN branch_code     char(3),
    ADD COLUMN branch_name     varchar(60),
    ADD COLUMN account_type    varchar(10),
    ADD COLUMN account_number  char(7),
    ADD COLUMN account_holder  varchar(48),
    ADD COLUMN paid_on         date,
    ADD COLUMN paid_reference  varchar(64),
    ADD COLUMN paid_by         varchar(128),
    ADD COLUMN returned_by     varchar(128);

-- A Stripe transfer has no bank account; a bank one has all of it and never anything of Stripe's, nor a state of
-- Stripe's alone (in doubt, reversed).
ALTER TABLE qb_transfer ADD CONSTRAINT qb_transfer_method CHECK (CASE method
    WHEN 'STRIPE' THEN bank_code IS NULL AND account_number IS NULL AND paid_reference IS NULL
    ELSE bank_code IS NOT NULL AND bank_name IS NOT NULL AND branch_code IS NOT NULL AND branch_name IS NOT NULL
        AND account_type IS NOT NULL AND account_number IS NOT NULL AND account_holder IS NOT NULL
        AND stripe_transfer_id IS NULL AND status NOT IN ('CHECK', 'REVERSED') END);

-- Settled transfers have both postings and Stripe's transfer, or (paid by bank) the statement's entry and who paid it.
ALTER TABLE qb_transfer DROP CONSTRAINT qb_transfer_sent;
ALTER TABLE qb_transfer ADD CONSTRAINT qb_transfer_sent CHECK (status NOT IN ('SENT', 'REVERSED')
    OR (send_transaction_id IS NOT NULL AND settle_transaction_id IS NOT NULL
        AND (stripe_transfer_id IS NOT NULL OR (method = 'BANK' AND paid_on IS NOT NULL
            AND paid_reference IS NOT NULL AND paid_by IS NOT NULL))));

-- One entry of the bank statement pays one withdrawal at most.
CREATE UNIQUE INDEX uk_qb_transfer_paid_reference ON qb_transfer (paid_on, paid_reference)
    WHERE paid_reference IS NOT NULL;
-- Finance's list of the withdrawals to review and to pay.
CREATE INDEX qb_transfer_bank_idx ON qb_transfer (status, created_at) WHERE method = 'BANK';
