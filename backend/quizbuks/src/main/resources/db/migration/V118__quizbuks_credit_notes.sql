-- F-M7 (docs/quizbuks/plans/impl/F-M.md §10): credit notes. A publication that ended early gives back the unused days of
-- its audience fee (the refund job), and finance corrects any fee by hand (four eyes): never by changing the fee, but by
-- a credit note written once, which pays the fee's invoice, gives back what a prepaid fee was paid (by the parts it was
-- paid with, the last first) and leaves the rest as credit that pays the sponsor's next invoices. Only new tables and
-- columns: the append-only tables gain columns, nothing is renamed.

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

-- A credit note: written once; the posting's source (debit 4120 and 2150 / credit 1130).
SELECT quizbuks_create_temporal_table('qb_fee_credit_note_version', 'credit_note_id', '
    credit_note_no  varchar(30)    NOT NULL,
    sponsor_id      varchar(128)   NOT NULL,
    operator_id     varchar(36),
    charge_id       varchar(36)    NOT NULL,
    invoice_id      varchar(36),
    invoice_no      varchar(30),
    publication_id  varchar(36),
    billing_mode    varchar(10)    NOT NULL CHECK (billing_mode IN (''PREPAID'', ''POSTPAID'')),
    reason          varchar(20)    NOT NULL CHECK (reason IN (''AUDIENCE_UNUSED'', ''MANUAL'')),
    end_reason      varchar(20),
    used_days       numeric(5, 0),
    note            varchar(500),
    amount          numeric(19, 0) NOT NULL CHECK (amount > 0),
    tax_rate        numeric(5, 4)  NOT NULL,
    tax             numeric(19, 0) NOT NULL CHECK (tax >= 0),
    total           numeric(19, 0) NOT NULL CHECK (total = amount + tax),
    applied_own     numeric(19, 0) NOT NULL CHECK (applied_own >= 0),
    refunded_cash   numeric(19, 0) NOT NULL CHECK (refunded_cash >= 0),
    refunded_credit numeric(19, 0) NOT NULL CHECK (refunded_credit >= 0),
    voided_credit   numeric(19, 0) NOT NULL CHECK (voided_credit >= 0),
    source_key      varchar(100)   NOT NULL,
    issued_at       timestamptz    NOT NULL,
    issued_by       varchar(128),
    CONSTRAINT qb_fee_credit_note_parts CHECK (applied_own + refunded_cash + refunded_credit <= total
        AND voided_credit <= refunded_credit)');
CREATE UNIQUE INDEX qb_fee_credit_note_version_once_idx ON qb_fee_credit_note_version (credit_note_id);
CREATE UNIQUE INDEX qb_fee_credit_note_version_no_idx ON qb_fee_credit_note_version (credit_note_no);
CREATE UNIQUE INDEX qb_fee_credit_note_version_source_idx ON qb_fee_credit_note_version (sponsor_id, source_key);
CREATE INDEX qb_fee_credit_note_version_charge_idx ON qb_fee_credit_note_version (charge_id);
CREATE INDEX qb_fee_credit_note_version_operator_idx ON qb_fee_credit_note_version (operator_id, issued_at);

-- What a note's credit paid of an invoice (its own, another unpaid one, or a later one): written once.
SELECT quizbuks_create_temporal_table('qb_fee_credit_note_use_version', 'use_id', '
    credit_note_id varchar(36)    NOT NULL,
    sponsor_id     varchar(128)   NOT NULL,
    invoice_id     varchar(36)    NOT NULL,
    amount         numeric(19, 0) NOT NULL CHECK (amount > 0),
    used_at        timestamptz    NOT NULL');
CREATE UNIQUE INDEX qb_fee_credit_note_use_version_once_idx ON qb_fee_credit_note_use_version (use_id);
CREATE INDEX qb_fee_credit_note_use_version_note_idx ON qb_fee_credit_note_use_version (credit_note_id);
CREATE INDEX qb_fee_credit_note_use_version_invoice_idx ON qb_fee_credit_note_use_version (invoice_id);
CREATE INDEX qb_fee_credit_note_use_version_sponsor_idx ON qb_fee_credit_note_use_version (sponsor_id);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);

-- A note's issued document (the sponsor issues it when they first ask, as an invoice's).
CREATE TABLE qb_fee_credit_note_document (
    credit_note_id  varchar(36)  PRIMARY KEY,
    sponsor_id      varchar(128) NOT NULL,
    document_run_id varchar(36)  NOT NULL,
    document_no     varchar(100) NOT NULL,
    issued_at       timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 1
);

-- An invoice paid by credit: what credit notes paid of it. Owed = total - credit_applied - paid_amount.
ALTER TABLE qb_fee_invoice ADD COLUMN credit_applied numeric(19, 0) NOT NULL DEFAULT 0;
ALTER TABLE qb_fee_invoice DROP CONSTRAINT qb_fee_invoice_paid_amount;
ALTER TABLE qb_fee_invoice ADD CONSTRAINT qb_fee_invoice_paid_amount CHECK (paid_amount >= 0 AND credit_applied >= 0
    AND paid_amount + credit_applied <= total
    AND (status <> 'PAID' OR paid_amount + credit_applied = total)
    AND (status <> 'PARTIAL' OR (paid_amount + credit_applied > 0 AND paid_amount + credit_applied < total)));

-- A credit note's parts given back (negative) aim at it.
ALTER TABLE qb_fee_payment_version DROP CONSTRAINT qb_fee_payment_version_target_entity_check;
ALTER TABLE qb_fee_payment_version ADD CONSTRAINT qb_fee_payment_version_target_entity_check
    CHECK (target_entity IN ('QbFeeCharge', 'QbFeeInvoice', 'QbPrepayment', 'QbFeeCreditNote'));

-- The audience fee's refund decided once (§10.3-6): when and how. The refund job only takes those not decided.
ALTER TABLE qb_prepayment ADD COLUMN refund_decided_at timestamptz;
ALTER TABLE qb_prepayment ADD COLUMN refund_outcome varchar(20)
    CHECK (refund_outcome IN ('REFUNDED', 'CREDITED', 'NONE_SWITCH_OFF', 'NONE_REASON', 'NONE_ZERO', 'NONE_NO_PERIOD'));
ALTER TABLE qb_prepayment ADD CONSTRAINT qb_prepayment_refund_decided
    CHECK ((refund_decided_at IS NULL) = (refund_outcome IS NULL));
CREATE INDEX qb_prepayment_refund_due_idx ON qb_prepayment (purpose, charged_at)
    WHERE refund_decided_at IS NULL AND charged_at IS NOT NULL;

-- A credit note by hand is a fee request of its own kind (the same four eyes): the fee credited, the amount, why;
-- and the note the approval made.
ALTER TABLE qb_fee_request DROP CONSTRAINT qb_fee_request_kind_check;
ALTER TABLE qb_fee_request ADD CONSTRAINT qb_fee_request_kind_check
    CHECK (kind IN ('MANUAL', 'VOID', 'CREDIT', 'CREDIT_NOTE'));
ALTER TABLE qb_fee_request DROP CONSTRAINT qb_fee_request_kind;
ALTER TABLE qb_fee_request
    ADD COLUMN credited_charge_id varchar(36),
    ADD COLUMN credit_note_id     varchar(36);
ALTER TABLE qb_fee_request ADD CONSTRAINT qb_fee_request_kind
    CHECK ((kind = 'MANUAL' AND amount IS NOT NULL AND invoice_id IS NULL AND valid_days IS NULL
            AND credited_charge_id IS NULL)
        OR (kind = 'VOID' AND invoice_id IS NOT NULL AND valid_days IS NULL AND credited_charge_id IS NULL)
        OR (kind = 'CREDIT' AND amount IS NOT NULL AND invoice_id IS NULL AND credited_charge_id IS NULL)
        OR (kind = 'CREDIT_NOTE' AND amount IS NOT NULL AND credited_charge_id IS NOT NULL AND valid_days IS NULL));

-- The operator settlement's cost of the month's credit notes (C3): the operator's share of them, carried as the
-- prospecting cost is.
ALTER TABLE qb_operator_settlement ADD COLUMN credit_notes numeric(19, 0) NOT NULL DEFAULT 0;
ALTER TABLE qb_operator_settlement ADD COLUMN credit_note_deduction numeric(19, 0) NOT NULL DEFAULT 0;
