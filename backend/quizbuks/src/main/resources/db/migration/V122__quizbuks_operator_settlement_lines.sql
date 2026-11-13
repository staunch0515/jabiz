-- F-M12 (docs/quizbuks/plans/impl/F-M12.md): operators' settlements count every fee and every part of a payment made with
-- granted credit exactly once. A settlement writes each one it counted as a line; the unique index on
-- (line_kind, source_id, charge_id) is what keeps them from being counted twice (or missed) however the settlements run.
-- Only new tables and columns: the append-only tables gain columns, nothing is renamed.

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

-- One line of a settlement, written once. CHARGE: a fee counted (every kind is written; what the operator does not share
-- in is `excluded`); VOID: a counted fee whose invoice was voided since (negative); GIFT: one part of a payment made with
-- granted credit (negative when given back). For CHARGE and VOID the charge_id is the source_id: a unique index does
-- not hold for a null, so none is left empty. For GIFT the source_id is the payment's part (QbFeePayment) and the
-- charge_id the fee it paid (or gave back). share_rate and recovery_rate are those of the settlement that counted the
-- fee: a later void or giving back is taken at the same rates. recover_base (CHARGE only) is the part of the fee
-- (before tax) whose credit used is taken back, decided when the fee was counted.
SELECT quizbuks_create_temporal_table('qb_operator_settlement_line_version', 'line_id', '
    settlement_id  varchar(36)    NOT NULL REFERENCES qb_operator_settlement (settlement_id),
    operator_id    varchar(36)    NOT NULL,
    sponsor_id     varchar(128)   NOT NULL,
    month          varchar(7)     NOT NULL,
    line_kind      varchar(10)    NOT NULL CHECK (line_kind IN (''CHARGE'', ''VOID'', ''GIFT'')),
    source_id      varchar(36)    NOT NULL,
    charge_id      varchar(36)    NOT NULL,
    fee_kind       varchar(20)    NOT NULL,
    period         varchar(7)     NOT NULL,
    late           boolean        NOT NULL DEFAULT false,
    occurred_at    timestamptz    NOT NULL,
    amount         numeric(19, 0) NOT NULL,
    excluded       numeric(19, 0) NOT NULL DEFAULT 0,
    gift_gross     numeric(19, 0) NOT NULL DEFAULT 0,
    grant_source   varchar(20),
    grant_id       varchar(36),
    in_base        numeric(19, 0) NOT NULL DEFAULT 0,
    recover_gross  numeric(19, 0) NOT NULL DEFAULT 0,
    no_share       boolean        NOT NULL DEFAULT false,
    recover_base   numeric(19, 0) NOT NULL DEFAULT 0,
    share_rate     numeric(5, 4)  NOT NULL,
    recovery_rate  numeric(5, 4)  NOT NULL,
    CONSTRAINT qb_operator_settlement_line_source CHECK (line_kind = ''GIFT'' OR source_id = charge_id)');
CREATE UNIQUE INDEX qb_operator_settlement_line_version_once_idx ON qb_operator_settlement_line_version (line_id);
CREATE UNIQUE INDEX qb_operator_settlement_line_version_source_idx
    ON qb_operator_settlement_line_version (line_kind, source_id, charge_id);
CREATE INDEX qb_operator_settlement_line_version_settlement_idx ON qb_operator_settlement_line_version (settlement_id);
CREATE INDEX qb_operator_settlement_line_version_operator_idx
    ON qb_operator_settlement_line_version (operator_id, line_kind, month);
CREATE INDEX qb_operator_settlement_line_version_charge_idx ON qb_operator_settlement_line_version (charge_id);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);

-- The part of a fee that stays out of the operator's share (its cost to the platform, which the caller states).
ALTER TABLE qb_fee_charge_version ADD COLUMN share_excluded numeric(19, 0) NOT NULL DEFAULT 0
    CHECK (share_excluded >= 0 AND share_excluded <= amount);

-- What a settlement of the new kind counted and took back. covered_from (the switch point: the end of the operator's
-- last settlement of the old kind) is empty on the old settlements, which tells the two apart; the new columns are 0 on
-- them. `revenue` (the commission, 4110) stays for the old rows and is always 0 on the new ones.
ALTER TABLE qb_operator_settlement
    ADD COLUMN covered_from        timestamptz,
    ADD COLUMN gift_paid           numeric(19, 0) NOT NULL DEFAULT 0,
    ADD COLUMN gift_share_deduction numeric(19, 0) NOT NULL DEFAULT 0,
    ADD COLUMN void_deduction      numeric(19, 0) NOT NULL DEFAULT 0,
    ADD COLUMN cost_excluded       numeric(19, 0) NOT NULL DEFAULT 0,
    ADD COLUMN gift_used           numeric(19, 0) NOT NULL DEFAULT 0,
    ADD COLUMN gift_due            numeric(19, 0) NOT NULL DEFAULT 0,
    ADD COLUMN gift_carried_in     numeric(19, 0) NOT NULL DEFAULT 0 CHECK (gift_carried_in >= 0),
    ADD COLUMN gift_recovered      numeric(19, 0) NOT NULL DEFAULT 0,
    ADD COLUMN gift_carried_out    numeric(19, 0) NOT NULL DEFAULT 0 CHECK (gift_carried_out >= 0);
