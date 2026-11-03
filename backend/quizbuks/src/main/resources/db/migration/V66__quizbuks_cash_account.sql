-- Q87 (docs/quizbuks/plans/Q87-fee-account.md): the sponsor's cash account and the platform's fees.
--
-- The cash account is a third kind of wallet row (2160 for the sponsor); a top-up names what it becomes (Kudos at
-- once, as every top-up before, or cash). Cash and Kudos are exchanged 1 : 1 (qb_kudos_exchange, the postings'
-- source). The platform's fees are written once (qb_fee_charge_version, append-only), invoiced (qb_fee_invoice,
-- an ordinary table: its state changes), and charged by hand or voided with another administrator's approval
-- (qb_fee_request). A sponsor's billing mode and credit limit are on their onboarding (new columns only: the
-- table is sealed). The operator settlement sums the platform fees of the month beside the commission.

-- The wallet's third kind.
ALTER TABLE qb_wallet DROP CONSTRAINT qb_wallet_kind_check;
ALTER TABLE qb_wallet ADD CONSTRAINT qb_wallet_kind_check CHECK (kind IN ('SPONSOR', 'TAKER', 'CASH'));

-- What a top-up becomes; those from before are Kudos.
ALTER TABLE qb_topup ADD COLUMN target varchar(10) NOT NULL DEFAULT 'KUDOS'
    CONSTRAINT qb_topup_target_check CHECK (target IN ('KUDOS', 'CASH'));

-- Cash to Kudos and back (D-Q87-3): the posting names the exchange as its source.
CREATE TABLE qb_kudos_exchange (
    exchange_id           varchar(36)    PRIMARY KEY,
    sponsor_id            varchar(128)   NOT NULL,
    direction             varchar(4)     NOT NULL CHECK (direction IN ('BUY', 'SELL')),
    amount                numeric(19, 0) NOT NULL CHECK (amount > 0),
    exchanged_at          timestamptz    NOT NULL,
    ledger_transaction_id varchar(64),
    version               bigint         NOT NULL DEFAULT 1,
    CONSTRAINT qb_kudos_exchange_posting UNIQUE (ledger_transaction_id)
);
CREATE INDEX qb_kudos_exchange_sponsor_idx ON qb_kudos_exchange (sponsor_id, exchanged_at DESC);

-- The sponsor's billing (D-Q87-5): how they pay the platform's fees; none means the platform's default.
ALTER TABLE qb_sponsor_version ADD COLUMN billing_mode varchar(10)
    CONSTRAINT qb_sponsor_billing_mode_check CHECK (billing_mode IN ('PREPAID', 'POSTPAID'));
ALTER TABLE qb_sponsor_version ADD COLUMN credit_limit numeric(19, 0) CHECK (credit_limit >= 0);
ALTER TABLE qb_sponsor_version ADD COLUMN billing_reason varchar(500);
ALTER TABLE qb_sponsor_version ADD COLUMN billing_set_by varchar(128);
ALTER TABLE qb_sponsor_version ADD COLUMN billing_set_at timestamptz;

-- The platform fees charged (D-Q87-4): written once, the postings' source.
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

SELECT quizbuks_create_temporal_table('qb_fee_charge_version', 'charge_id', '
    sponsor_id   varchar(128)   NOT NULL,
    operator_id  varchar(36),
    billing_mode varchar(10)    NOT NULL CHECK (billing_mode IN (''PREPAID'', ''POSTPAID'')),
    kind         varchar(10)    NOT NULL CHECK (kind IN (''MONTHLY'', ''MANUAL'')),
    period       varchar(7)     NOT NULL,
    amount       numeric(19, 0) NOT NULL CHECK (amount > 0),
    tax_rate     numeric(5, 4)  NOT NULL,
    tax          numeric(19, 0) NOT NULL CHECK (tax >= 0),
    total        numeric(19, 0) NOT NULL CHECK (total = amount + tax),
    reason       varchar(500),
    source_key   varchar(100),
    charged_at   timestamptz    NOT NULL,
    charged_by   varchar(128)');

-- Written once: one row per key; one monthly fee per sponsor and month.
CREATE UNIQUE INDEX qb_fee_charge_version_once_idx ON qb_fee_charge_version (charge_id);
CREATE UNIQUE INDEX qb_fee_charge_version_monthly_idx ON qb_fee_charge_version (sponsor_id, period)
    WHERE kind = 'MONTHLY';
CREATE INDEX qb_fee_charge_version_sponsor_idx ON qb_fee_charge_version (sponsor_id, period);
CREATE INDEX qb_fee_charge_version_operator_idx ON qb_fee_charge_version (operator_id, charged_at);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);

-- The invoices (D-Q87-5, D-Q87-6): a prepaid charge's own, or a postpaid month's.
CREATE TABLE qb_fee_invoice (
    invoice_id             varchar(36)    PRIMARY KEY,
    invoice_no             varchar(30)    NOT NULL,
    sponsor_id             varchar(128)   NOT NULL,
    operator_id            varchar(36),
    billing_mode           varchar(10)    NOT NULL CHECK (billing_mode IN ('PREPAID', 'POSTPAID')),
    period                 varchar(7)     NOT NULL,
    charge_id              varchar(36),
    amount                 numeric(19, 0) NOT NULL CHECK (amount >= 0),
    tax                    numeric(19, 0) NOT NULL CHECK (tax >= 0),
    total                  numeric(19, 0) NOT NULL CHECK (total = amount + tax),
    status                 varchar(10)    NOT NULL CHECK (status IN ('OPEN', 'OVERDUE', 'PAID', 'VOID')),
    issued_at              timestamptz    NOT NULL,
    due_on                 date           NOT NULL,
    paid_at                timestamptz,
    payment_transaction_id varchar(64),
    voided_at              timestamptz,
    voided_by              varchar(128),
    void_reason            varchar(500),
    void_requested_by      varchar(128),
    approval_request_id    varchar(36),
    reminders_sent         numeric(3, 0)  NOT NULL DEFAULT 0,
    last_reminder_at       timestamptz,
    document_no            varchar(100),
    document_run_id        varchar(36),
    version                bigint         NOT NULL DEFAULT 1,
    CONSTRAINT uk_qb_fee_invoice_no UNIQUE (invoice_no),
    CONSTRAINT qb_fee_invoice_paid CHECK ((status = 'PAID') = (paid_at IS NOT NULL AND payment_transaction_id IS NOT NULL)
        OR status = 'VOID'),
    CONSTRAINT qb_fee_invoice_payment UNIQUE (payment_transaction_id)
);
-- One invoice per prepaid charge; one period invoice per sponsor and month.
CREATE UNIQUE INDEX qb_fee_invoice_charge_idx ON qb_fee_invoice (charge_id) WHERE charge_id IS NOT NULL;
CREATE UNIQUE INDEX qb_fee_invoice_period_idx ON qb_fee_invoice (sponsor_id, period) WHERE charge_id IS NULL;
CREATE INDEX qb_fee_invoice_sponsor_idx ON qb_fee_invoice (sponsor_id, status, issued_at DESC);
CREATE INDEX qb_fee_invoice_due_idx ON qb_fee_invoice (due_on) WHERE status IN ('OPEN', 'OVERDUE');

-- What headquarters asks a second administrator to approve: a fee by hand, an invoice voided.
CREATE TABLE qb_fee_request (
    request_id          varchar(36)    PRIMARY KEY,
    kind                varchar(10)    NOT NULL CHECK (kind IN ('MANUAL', 'VOID')),
    sponsor_id          varchar(128)   NOT NULL,
    amount              numeric(19, 0) CHECK (amount > 0),
    invoice_id          varchar(36),
    reason              varchar(500)   NOT NULL,
    status              varchar(10)    NOT NULL CHECK (status IN ('REVIEW', 'DONE', 'REJECTED')),
    requested_by        varchar(128)   NOT NULL,
    requested_at        timestamptz    NOT NULL,
    approval_request_id varchar(36),
    decided_at          timestamptz,
    charge_id           varchar(36),
    version             bigint         NOT NULL DEFAULT 1,
    CONSTRAINT qb_fee_request_kind CHECK ((kind = 'MANUAL' AND amount IS NOT NULL AND invoice_id IS NULL)
        OR (kind = 'VOID' AND invoice_id IS NOT NULL))
);
CREATE INDEX qb_fee_request_status_idx ON qb_fee_request (status, requested_at DESC);

-- The operator settlement's platform fees of the month (D-Q87-8); 0 for the months before.
ALTER TABLE qb_operator_settlement ADD COLUMN platform_fees numeric(19, 0) NOT NULL DEFAULT 0;
