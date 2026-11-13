-- F-M9 (docs/quizbuks/first-launch-spec.md F-M9, docs/quizbuks/plans/impl/F-M9.md): a sponsor's AI credit and the AI fee.
-- The credit is one long-lived prepayment a sponsor (account 2180, purpose AI); a request of the AI reserves the most it
-- may cost, the job's end works out its fee from the usage the provider's answers reported, and the fees of a month are
-- charged from the credit the month after.

-- On the job, frozen at the request: whether it is charged (the switch qb.ai.fee.enabled then), the operator the fee will
-- belong to (none before the sponsor's onboarding), the markup and the exchange rate. Written at the job's end: what it
-- used that is the sponsor's (billable_usd) and the platform's (unrecorded_usd: the estimates of the calls that got no
-- answer and of the attempts that recorded nothing), the fee before tax, what the platform took on when the credit fell
-- short, and the month it belongs to (the outcome's month); the monthly charge writes the charge it went into.
ALTER TABLE qb_ai_job ADD COLUMN fee_mode varchar(10) NOT NULL DEFAULT 'NONE'
    CONSTRAINT qb_ai_job_fee_mode CHECK (fee_mode IN ('NONE', 'CREDIT'));
ALTER TABLE qb_ai_job ADD COLUMN fee_operator_id varchar(36);
ALTER TABLE qb_ai_job ADD COLUMN fee_markup numeric(6, 2) CONSTRAINT qb_ai_job_fee_markup CHECK (fee_markup > 0);
ALTER TABLE qb_ai_job ADD COLUMN fee_usd_jpy numeric(8, 2) CONSTRAINT qb_ai_job_fee_usd_jpy CHECK (fee_usd_jpy > 0);
ALTER TABLE qb_ai_job ADD COLUMN reserved_yen numeric(19, 0) NOT NULL DEFAULT 0
    CONSTRAINT qb_ai_job_reserved_yen CHECK (reserved_yen >= 0);
ALTER TABLE qb_ai_job ADD COLUMN billable_usd numeric(14, 6) CONSTRAINT qb_ai_job_billable_usd CHECK (billable_usd >= 0);
ALTER TABLE qb_ai_job ADD COLUMN unrecorded_usd numeric(14, 6)
    CONSTRAINT qb_ai_job_unrecorded_usd CHECK (unrecorded_usd >= 0);
ALTER TABLE qb_ai_job ADD COLUMN fee_yen numeric(19, 0) CONSTRAINT qb_ai_job_fee_yen CHECK (fee_yen >= 0);
ALTER TABLE qb_ai_job ADD COLUMN absorbed_yen numeric(19, 0) CONSTRAINT qb_ai_job_absorbed_yen CHECK (absorbed_yen >= 0);
ALTER TABLE qb_ai_job ADD COLUMN fee_charge_id varchar(36);
ALTER TABLE qb_ai_job ADD COLUMN fee_period varchar(7)
    CONSTRAINT qb_ai_job_fee_period CHECK (fee_period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$');
-- A job that is not charged reserves nothing and goes into no charge; a charged one knows its markup and rate.
ALTER TABLE qb_ai_job ADD CONSTRAINT qb_ai_job_fee_none
    CHECK (fee_mode = 'CREDIT' OR (reserved_yen = 0 AND fee_yen IS NULL AND fee_charge_id IS NULL));
ALTER TABLE qb_ai_job ADD CONSTRAINT qb_ai_job_fee_credit
    CHECK (fee_mode = 'NONE' OR (fee_markup IS NOT NULL AND fee_usd_jpy IS NOT NULL));
-- The jobs that still hold part of the credit (reserved, or finished and not yet charged): read at every request and by
-- the monthly charge.
CREATE INDEX qb_ai_job_fee_open_idx ON qb_ai_job (sponsor_id) WHERE fee_mode = 'CREDIT' AND fee_charge_id IS NULL;

-- One open AI credit a sponsor (it has no publication, so the publication's unique index does not hold it).
CREATE UNIQUE INDEX qb_prepayment_ai_open_idx ON qb_prepayment (sponsor_id) WHERE purpose = 'AI' AND status = 'OPEN';

-- The AI fee is a kind of charge (the integrity seal's digest reads the values, not the constraint: decision D58).
ALTER TABLE qb_fee_charge_version DROP CONSTRAINT qb_fee_charge_version_kind_check;
ALTER TABLE qb_fee_charge_version ADD CONSTRAINT qb_fee_charge_version_kind_check
    CHECK (kind IN ('MONTHLY', 'MANUAL', 'AUDIENCE', 'DISTRIBUTION', 'EXPOSURE', 'AI'));

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

-- Every movement of a sponsor's AI credit, written once: a purchase (in), a monthly charge, a return and an expiry of
-- fee credit (out). The amount is signed (in positive); credit and cash are its parts from the fee credit and the cash
-- (a charge's from the credit's gift parts first); balance is the credit's remaining after it.
SELECT quizbuks_create_temporal_table('qb_ai_credit_movement_version', 'movement_id', '
    sponsor_id   varchar(128)   NOT NULL,
    prepayment_id varchar(36)   NOT NULL,
    kind         varchar(10)    NOT NULL CHECK (kind IN (''BUY'', ''RETURN'', ''CHARGE'', ''EXPIRE'')),
    amount       numeric(19, 0) NOT NULL CHECK (amount <> 0),
    credit       numeric(19, 0) NOT NULL,
    cash         numeric(19, 0) NOT NULL,
    balance      numeric(19, 0) NOT NULL CHECK (balance >= 0),
    operator_id  varchar(36),
    reference    varchar(100),
    moved_at     timestamptz    NOT NULL,
    CONSTRAINT qb_ai_credit_movement_parts CHECK (credit + cash = amount),
    CONSTRAINT qb_ai_credit_movement_sign CHECK ((kind = ''BUY'') = (amount > 0))');

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);

-- Written once: one row per key.
CREATE UNIQUE INDEX qb_ai_credit_movement_version_once_idx ON qb_ai_credit_movement_version (movement_id);
CREATE INDEX qb_ai_credit_movement_version_sponsor_idx ON qb_ai_credit_movement_version (sponsor_id, moved_at);
CREATE INDEX qb_ai_credit_movement_version_prepayment_idx ON qb_ai_credit_movement_version (prepayment_id);
