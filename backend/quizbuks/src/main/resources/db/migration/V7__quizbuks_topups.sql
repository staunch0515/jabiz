-- Top-ups and the daily reconciliation (docs/quizbuks/02-design.md section 3.4, docs/quizbuks/plans/Q6-payments.md,
-- Q6a). A top-up is an ordinary table, written only by the processes under the sponsor's wallet lock; the constraints
-- hold whatever a process does: one top-up per Stripe session, payment and ledger transaction, a paid top-up always
-- with its posting, and never more taken back than came in. The reconciliation's runs and items are written once.

CREATE TABLE qb_topup (
    topup_id               varchar(36)    PRIMARY KEY,
    sponsor_id             varchar(128)   NOT NULL,
    amount                 numeric(19, 0) NOT NULL CHECK (amount > 0),
    status                 varchar(20)    NOT NULL CHECK (status IN ('OPENING', 'OPEN', 'PAID', 'EXPIRED', 'FAILED')),
    created_at             timestamptz    NOT NULL,
    checkout_session_id    varchar(255),
    checkout_url           varchar(2048),
    expires_at             timestamptz,
    failure_code           varchar(100),
    payment_intent_id      varchar(255),
    charge_id              varchar(255),
    balance_transaction_id varchar(255),
    livemode               boolean,
    paid_at                timestamptz,
    confirmed_by           varchar(64),
    ledger_transaction_id  varchar(64),
    stripe_fee             numeric(19, 0) CHECK (stripe_fee >= 0),
    net_amount             numeric(19, 0),
    fee_transaction_id     varchar(64),
    presentment_currency   varchar(3),
    presentment_amount     numeric(19, 0),
    fx_rate                numeric(20, 10),
    refunded_amount        numeric(19, 0) NOT NULL DEFAULT 0,
    refund_shortfall       numeric(19, 0) NOT NULL DEFAULT 0 CHECK (refund_shortfall >= 0),
    dispute_id             varchar(255),
    dispute_status         varchar(20)    CHECK (dispute_status IN ('OPEN', 'WON', 'LOST', 'CLOSED')),
    dispute_amount         numeric(19, 0),
    dispute_clawback       numeric(19, 0) NOT NULL DEFAULT 0,
    check_reason           varchar(40),
    check_requested_at     timestamptz,
    version                bigint         NOT NULL,
    CONSTRAINT uk_qb_topup_session UNIQUE (checkout_session_id),
    CONSTRAINT uk_qb_topup_payment UNIQUE (payment_intent_id),
    CONSTRAINT qb_topup_posting UNIQUE (ledger_transaction_id),
    CONSTRAINT qb_topup_fee_posting UNIQUE (fee_transaction_id),
    CONSTRAINT qb_topup_paid_posted CHECK (status <> 'PAID' OR (ledger_transaction_id IS NOT NULL
        AND payment_intent_id IS NOT NULL)),
    CONSTRAINT qb_topup_taken_back CHECK (refunded_amount >= 0 AND dispute_clawback >= 0
        AND refunded_amount + dispute_clawback <= amount)
);
-- A sponsor's own top-ups, newest first; the open ones whose checkout expired (the stale job); the administrators'
-- list by state.
CREATE INDEX qb_topup_sponsor_idx ON qb_topup (sponsor_id, created_at DESC);
CREATE INDEX qb_topup_unsettled_idx ON qb_topup (expires_at) WHERE status IN ('OPENING', 'OPEN');
CREATE INDEX qb_topup_status_idx ON qb_topup (status, created_at);

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

-- One run per scheduled time, its summary the counts of each check (canonical JSON).
SELECT quizbuks_create_temporal_table('qb_recon_run_version', 'run_id', '
    scheduled_time timestamptz   NOT NULL,
    status         varchar(10)   NOT NULL CHECK (status IN (''OK'', ''DIFF'')),
    checked_at     timestamptz   NOT NULL,
    item_count     numeric(9, 0) NOT NULL,
    summary        varchar(2000) NOT NULL');

-- One item per difference: what the truth says, what was found.
SELECT quizbuks_create_temporal_table('qb_recon_item_version', 'item_id', '
    run_id     uuid           NOT NULL REFERENCES entity_registry (entity_id),
    check_code varchar(30)    NOT NULL,
    party_id   varchar(128),
    reference  varchar(100),
    expected   numeric(19, 0) NOT NULL,
    actual     numeric(19, 0) NOT NULL,
    difference numeric(19, 0) NOT NULL');

-- Written once: one row per key, and one run per scheduled time.
CREATE UNIQUE INDEX qb_recon_run_version_once_idx ON qb_recon_run_version (run_id);
CREATE UNIQUE INDEX qb_recon_run_version_time_idx ON qb_recon_run_version (scheduled_time);
CREATE UNIQUE INDEX qb_recon_item_version_once_idx ON qb_recon_item_version (item_id);
CREATE INDEX qb_recon_item_version_run_idx ON qb_recon_item_version (run_id);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
