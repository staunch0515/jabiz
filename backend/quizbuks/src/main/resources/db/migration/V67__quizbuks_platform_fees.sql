-- Q87b (docs/quizbuks/plans/Q87-fee-account.md §11): platform fees, billing modes, invoices and their requests.

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

-- A charge, written once: the posting's source; its invoice is the sponsor's of its month.
SELECT quizbuks_create_temporal_table('qb_fee_charge_version', 'charge_id', '
    sponsor_id    varchar(128)   NOT NULL,
    kind          varchar(20)    NOT NULL,
    period        varchar(7)     NOT NULL CHECK (period ~ ''^[0-9]{4}-[0-9]{2}$''),
    amount        numeric(19, 0) NOT NULL,
    tax_rate      numeric(5, 4)  NOT NULL CHECK (tax_rate >= 0 AND tax_rate < 1),
    tax           numeric(19, 0) NOT NULL,
    settlement    varchar(12)    NOT NULL CHECK (settlement IN (''CASH'', ''RECEIVABLE'')),
    operator_id   varchar(64),
    source_entity varchar(64),
    source_id     varchar(64),
    note          varchar(500),
    charged_at    timestamptz    NOT NULL');
CREATE UNIQUE INDEX qb_fee_charge_version_once_idx ON qb_fee_charge_version (charge_id);
CREATE INDEX qb_fee_charge_version_sponsor_idx ON qb_fee_charge_version (sponsor_id, period);
CREATE INDEX qb_fee_charge_version_operator_idx ON qb_fee_charge_version (operator_id, period)
    WHERE operator_id IS NOT NULL;
-- The monthly fee once per sponsor and month.
CREATE UNIQUE INDEX qb_fee_charge_version_monthly_idx ON qb_fee_charge_version (sponsor_id, period)
    WHERE kind = 'MONTHLY';

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);

-- A sponsor's billing mode and credit; no row: the platform's default (qb.fee.default-billing-mode).
CREATE TABLE qb_sponsor_billing (
    billing_id   varchar(36)    PRIMARY KEY,
    sponsor_id   varchar(128)   NOT NULL,
    billing_mode varchar(10)    NOT NULL CHECK (billing_mode IN ('PREPAID', 'POSTPAID')),
    credit_limit numeric(19, 0) NOT NULL CHECK (credit_limit >= 0),
    reason       varchar(500)   NOT NULL,
    updated_at   timestamptz    NOT NULL,
    updated_by   varchar(128)   NOT NULL,
    version      bigint         NOT NULL,
    CONSTRAINT uk_qb_sponsor_billing_sponsor UNIQUE (sponsor_id)
);

-- A sponsor's invoice of a month.
CREATE TABLE qb_fee_invoice (
    invoice_id      varchar(36)    PRIMARY KEY,
    invoice_no      varchar(30)    NOT NULL,
    sponsor_id      varchar(128)   NOT NULL,
    period          varchar(7)     NOT NULL,
    billing_mode    varchar(10)    NOT NULL,
    amount          numeric(19, 0) NOT NULL,
    tax             numeric(19, 0) NOT NULL,
    total           numeric(19, 0) NOT NULL,
    paid_at_charge  numeric(19, 0) NOT NULL,
    due             numeric(19, 0) NOT NULL CHECK (due >= 0),
    adjustment      numeric(19, 0) NOT NULL,
    status          varchar(10)    NOT NULL CHECK (status IN ('PAID', 'ISSUED', 'OVERDUE', 'VOID')),
    issued_at       timestamptz    NOT NULL,
    due_on          date,
    paid_at         timestamptz,
    document_run_id varchar(36),
    reminders       numeric(3, 0)  NOT NULL DEFAULT 0,
    reminded_at     timestamptz,
    voided_at       timestamptz,
    version         bigint         NOT NULL,
    CONSTRAINT uk_qb_fee_invoice_no UNIQUE (invoice_no),
    CONSTRAINT uk_qb_fee_invoice_period UNIQUE (sponsor_id, period)
);
CREATE INDEX qb_fee_invoice_unpaid_idx ON qb_fee_invoice (due_on) WHERE status IN ('ISSUED', 'OVERDUE');

-- A payment from the cash account: of an invoice, or of a month's charges before its invoice (invoice_id null).
CREATE TABLE qb_fee_payment (
    payment_id varchar(36)    PRIMARY KEY,
    sponsor_id varchar(128)   NOT NULL,
    period     varchar(7)     NOT NULL,
    invoice_id varchar(36),
    amount     numeric(19, 0) NOT NULL CHECK (amount > 0),
    paid_at    timestamptz    NOT NULL,
    version    bigint         NOT NULL
);
CREATE INDEX qb_fee_payment_sponsor_idx ON qb_fee_payment (sponsor_id, period);

-- A charge by hand or a voiding, waiting for another administrator.
CREATE TABLE qb_fee_request (
    request_id          varchar(36)    PRIMARY KEY,
    kind                varchar(10)    NOT NULL CHECK (kind IN ('CHARGE', 'VOID')),
    sponsor_id          varchar(128)   NOT NULL,
    invoice_id          varchar(36),
    amount              numeric(19, 0),
    reason              varchar(500)   NOT NULL,
    status              varchar(10)    NOT NULL CHECK (status IN ('REVIEW', 'APPROVED', 'REJECTED')),
    requested_by        varchar(128)   NOT NULL,
    requested_at        timestamptz    NOT NULL,
    approval_request_id varchar(64),
    decided_at          timestamptz,
    version             bigint         NOT NULL,
    CHECK (kind <> 'CHARGE' OR amount > 0),
    CHECK (kind <> 'VOID' OR invoice_id IS NOT NULL)
);
-- One open voiding per invoice.
CREATE UNIQUE INDEX qb_fee_request_void_idx ON qb_fee_request (invoice_id) WHERE kind = 'VOID' AND status = 'REVIEW';
