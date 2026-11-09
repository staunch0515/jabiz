-- F-M1 (docs/quizbuks/plans/impl/F-M.md §M1): the account that pays the platform's fees - a sponsor's fee credit
-- (platform fee Kudos, account 2190, wallet kind FEE_CREDIT) by lot, their cash (2160) by lot, and every payment of a
-- fee split into the lots it was taken from (append-only). Invoices may be paid in part (PARTIAL).

-- The fee credit's wallet row beside the Kudos wallet and the cash account, under the same lock.
ALTER TABLE qb_wallet DROP CONSTRAINT qb_wallet_kind_check;
ALTER TABLE qb_wallet ADD CONSTRAINT qb_wallet_kind_check
    CHECK (kind IN ('SPONSOR', 'TAKER', 'CASH', 'FEE_CREDIT'));

-- One grant of fee credit (F-M2 grants them; F-M1 spends them), its remaining amount changed under the wallet lock.
CREATE TABLE qb_fee_credit_grant (
    grant_id    varchar(36)    PRIMARY KEY,
    sponsor_id  varchar(128)   NOT NULL,
    source      varchar(20)    NOT NULL CHECK (source IN ('ONBOARDING', 'LIGHT', 'MANUAL', 'CERTIFIED_TOP_UP')),
    source_key  varchar(100)   NOT NULL,
    operator_id varchar(36),
    amount      numeric(19, 0) NOT NULL CHECK (amount > 0),
    remaining   numeric(19, 0) NOT NULL,
    granted_at  timestamptz    NOT NULL,
    expires_at  timestamptz    NOT NULL,
    version     bigint         NOT NULL DEFAULT 1,
    CONSTRAINT uk_qb_fee_credit_grant_source UNIQUE (sponsor_id, source_key),
    CONSTRAINT qb_fee_credit_grant_remaining CHECK (remaining >= 0 AND remaining <= amount),
    CONSTRAINT qb_fee_credit_grant_expiry CHECK (expires_at > granted_at)
);
-- The payment reads a sponsor's open lots, the earliest to expire first.
CREATE INDEX qb_fee_credit_grant_open_idx ON qb_fee_credit_grant (sponsor_id, expires_at) WHERE remaining > 0;
CREATE INDEX qb_fee_credit_grant_sponsor_idx ON qb_fee_credit_grant (sponsor_id, granted_at DESC);

-- One sum of money that arrived in a sponsor's cash account; 2160 for them is the sum of their lots' remaining amounts.
CREATE TABLE qb_cash_lot (
    lot_id        varchar(36)    PRIMARY KEY,
    sponsor_id    varchar(128)   NOT NULL,
    source        varchar(10)    NOT NULL CHECK (source IN ('CARD', 'BANK', 'OPERATOR', 'OTHER')),
    amount        numeric(19, 0) NOT NULL CHECK (amount > 0),
    remaining     numeric(19, 0) NOT NULL,
    arrived_at    timestamptz    NOT NULL,
    source_entity varchar(40),
    source_id     varchar(64),
    version       bigint         NOT NULL DEFAULT 1,
    CONSTRAINT qb_cash_lot_remaining CHECK (remaining >= 0 AND remaining <= amount)
);
-- Spent in the order the money arrived: only the lots with money left are read.
CREATE INDEX qb_cash_lot_open_idx ON qb_cash_lot (sponsor_id, arrived_at) WHERE remaining > 0;
CREATE INDEX qb_cash_lot_sponsor_idx ON qb_cash_lot (sponsor_id, arrived_at DESC);
CREATE INDEX qb_cash_lot_source_idx ON qb_cash_lot (source_entity, source_id);

-- Each part of a payment of a fee or an invoice, written once (a voided invoice's: a part with the amount negated).
CREATE FUNCTION quizbuks_create_temporal_table(p_table text, p_key text, p_columns text) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('CREATE TABLE %I (
        row_id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        %I                uuid        NOT NULL REFERENCES entity_registry (entity_id),
        version_no        integer     NOT NULL,
        effect_start_time timestamptz NOT NULL,
        created_time      timestamptz NOT NULL,
        process_seq_id    bigint      NOT NULL REFERENCES op_process (process_seq_id),
        is_deleted        boolean     NOT NULL DEFAULT false,
        %s,
        CONSTRAINT %I UNIQUE (%I, version_no))',
        p_table, p_key, p_columns, p_table || '_uk', p_key);
    EXECUTE format('CREATE INDEX %I ON %I (%I, effect_start_time DESC, version_no DESC)',
        p_table || '_current_idx', p_table, p_key);
    EXECUTE format('CREATE INDEX %I ON %I (process_seq_id)', p_table || '_process_idx', p_table);
    PERFORM jabiz_protect_append_only(p_table::regclass);
END $$;

SELECT quizbuks_create_temporal_table('qb_fee_payment_version', 'payment_id', '
    sponsor_id     varchar(128)   NOT NULL,
    target_entity  varchar(40)    NOT NULL CHECK (target_entity IN (''QbFeeCharge'', ''QbFeeInvoice'')),
    target_id      varchar(36)    NOT NULL,
    sub_account    varchar(10)    NOT NULL CHECK (sub_account IN (''FEE_CREDIT'', ''CASH'')),
    grant_id       varchar(36),
    cash_lot_id    varchar(36),
    amount         numeric(19, 0) NOT NULL CHECK (amount <> 0),
    paid_at        timestamptz    NOT NULL,
    transaction_id varchar(64)    NOT NULL,
    CONSTRAINT qb_fee_payment_lot CHECK ((sub_account = ''FEE_CREDIT'' AND grant_id IS NOT NULL AND cash_lot_id IS NULL)
        OR (sub_account = ''CASH'' AND cash_lot_id IS NOT NULL AND grant_id IS NULL))');

-- Written once: one row per key.
CREATE UNIQUE INDEX qb_fee_payment_version_once_idx ON qb_fee_payment_version (payment_id);
CREATE INDEX qb_fee_payment_version_sponsor_idx ON qb_fee_payment_version (sponsor_id, paid_at DESC);
CREATE INDEX qb_fee_payment_version_target_idx ON qb_fee_payment_version (target_id);
CREATE INDEX qb_fee_payment_version_transaction_idx ON qb_fee_payment_version (transaction_id);
CREATE INDEX qb_fee_payment_version_grant_idx ON qb_fee_payment_version (grant_id) WHERE grant_id IS NOT NULL;
CREATE INDEX qb_fee_payment_version_lot_idx ON qb_fee_payment_version (cash_lot_id) WHERE cash_lot_id IS NOT NULL;

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);

-- An invoice paid in part: what has been paid of it. Before F-M1 an invoice was paid in full or not at all.
ALTER TABLE qb_fee_invoice ADD COLUMN paid_amount numeric(19, 0) NOT NULL DEFAULT 0;
UPDATE qb_fee_invoice SET paid_amount = total WHERE paid_at IS NOT NULL;
ALTER TABLE qb_fee_invoice ADD CONSTRAINT qb_fee_invoice_paid_amount CHECK (paid_amount >= 0 AND paid_amount <= total
    AND (status <> 'PAID' OR paid_amount = total)
    AND (status <> 'PARTIAL' OR (paid_amount > 0 AND paid_amount < total)));
ALTER TABLE qb_fee_invoice DROP CONSTRAINT qb_fee_invoice_status_check;
ALTER TABLE qb_fee_invoice ADD CONSTRAINT qb_fee_invoice_status_check
    CHECK (status IN ('OPEN', 'PARTIAL', 'OVERDUE', 'PAID', 'VOID'));
DROP INDEX qb_fee_invoice_due_idx;
CREATE INDEX qb_fee_invoice_due_idx ON qb_fee_invoice (due_on) WHERE status IN ('OPEN', 'PARTIAL', 'OVERDUE');

-- Each sponsor's cash as it stands is one lot ('OTHER', the record 'MIGRATION'): there is no record of the sums it
-- arrived in, so it is spent before whatever arrives after (coordinator's answer c3, 2026-11-09). Read from the ledger
-- (2160 for the sponsor, credit less debit), the truth the cache follows. The arrival time is the migration's own
-- now(): it is not a business time of any record, only the place of this lot in the order (no Clock in a migration).
INSERT INTO qb_cash_lot (lot_id, sponsor_id, source, amount, remaining, arrived_at, source_entity, source_id, version)
SELECT CAST(gen_random_uuid() AS varchar(36)), l.party, 'OTHER', l.balance, l.balance, now(), 'MIGRATION', NULL, 1
FROM (
    SELECT e.dimension_1 AS party,
           sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END) AS balance
    FROM ledger_entry_version e
    WHERE NOT e.is_deleted
      AND e.account_id IN (SELECT a.account_id FROM ledger_account_version a WHERE a.account_code = '2160')
      AND e.dimension_1 IS NOT NULL
    GROUP BY e.dimension_1) l
WHERE l.balance > 0;
