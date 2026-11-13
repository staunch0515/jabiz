-- F-M8 (docs/quizbuks/first-launch-spec.md F-M8, docs/quizbuks/plans/impl/F-M8.md): the exposure fee, charged a month at a
-- time for the Takers who watched a shared video used by a publication that is not its provider's, at a price per
-- exposure frozen at the submission, from a deposit set aside then (account 2180, purpose EXPOSURE).

-- An operator's price (an ordinary table: one row an operator, saved over in turns; no row = no exposure fee).
CREATE TABLE qb_exposure_price (
    price_id    varchar(36)    PRIMARY KEY,
    operator_id varchar(36)    NOT NULL UNIQUE,
    unit        numeric(19, 4) NOT NULL CHECK (unit > 0),
    reason      varchar(500)   NOT NULL,
    updated_at  timestamptz    NOT NULL,
    updated_by  varchar(128)   NOT NULL,
    version     bigint         NOT NULL
);

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

-- One settlement a publication and month (written once). The counts are cumulative: the amount of a row is what the
-- exposures up to it come to, rounded up, less what the rows before it charged; the tax is cumulative too (the tax on
-- everything so far, rounded down, less what the rows before it charged), so that a deposit is used up exactly.
SELECT quizbuks_create_temporal_table('qb_exposure_settlement_version', 'settlement_id', '
    publication_id    varchar(36)    NOT NULL,
    sponsor_id        varchar(128)   NOT NULL,
    operator_id       varchar(36),
    period            varchar(7)     NOT NULL CHECK (period ~ ''^[0-9]{4}-(0[1-9]|1[0-2])$''),
    from_count        bigint         NOT NULL CHECK (from_count >= 0),
    to_count          bigint         NOT NULL,
    counted           bigint         NOT NULL CHECK (counted >= 0),
    unit              numeric(19, 4) NOT NULL CHECK (unit > 0),
    cumulative_amount numeric(19, 0) NOT NULL CHECK (cumulative_amount >= 0),
    amount            numeric(19, 0) NOT NULL CHECK (amount >= 0),
    tax               numeric(19, 0) NOT NULL CHECK (tax >= 0),
    total             numeric(19, 0) NOT NULL CHECK (total >= 0),
    final             boolean        NOT NULL,
    charge_id         varchar(36),
    settled_at        timestamptz    NOT NULL,
    CONSTRAINT qb_exposure_settlement_count CHECK (to_count = from_count + counted),
    CONSTRAINT qb_exposure_settlement_charge CHECK ((amount = 0 AND tax = 0) = (charge_id IS NULL))');

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);

-- Written once: one row per key.
CREATE UNIQUE INDEX qb_exposure_settlement_version_once_idx ON qb_exposure_settlement_version (settlement_id);
-- One row a publication and month; one final row a publication (the monthly and the final job never both write).
CREATE UNIQUE INDEX qb_exposure_settlement_version_period_idx ON qb_exposure_settlement_version (publication_id, period);
CREATE UNIQUE INDEX qb_exposure_settlement_version_final_idx
    ON qb_exposure_settlement_version (publication_id) WHERE final;
CREATE INDEX qb_exposure_settlement_version_sponsor_idx ON qb_exposure_settlement_version (sponsor_id, period);
CREATE INDEX qb_exposure_settlement_version_period_only_idx ON qb_exposure_settlement_version (period);

-- The last purchase a low-exposure notice was sent for, a row a publication: one reminder for each amount bought (a top-up
-- makes the next one possible).
CREATE TABLE qb_exposure_notice (
    notice_id    varchar(36)   PRIMARY KEY,
    publication_id varchar(36) NOT NULL UNIQUE,
    -- The number bought when the reminder was sent (0: none sent yet); the next waits for a different number.
    notified_for numeric(9, 0) NOT NULL CHECK (notified_for >= 0),
    -- The exposures counted when the publication was last looked at, so that it is looked at again only when more
    -- were counted (the job's candidates are all those under half left; the real ratio is a parameter).
    checked_used numeric(12, 0) NOT NULL DEFAULT 0 CHECK (checked_used >= 0),
    notified_at  timestamptz   NOT NULL,
    version      bigint        NOT NULL
);

-- The exposure fee is a kind of charge (the integrity seal's digest reads the values, not the constraint: decision D58).
ALTER TABLE qb_fee_charge_version DROP CONSTRAINT qb_fee_charge_version_kind_check;
ALTER TABLE qb_fee_charge_version ADD CONSTRAINT qb_fee_charge_version_kind_check
    CHECK (kind IN ('MONTHLY', 'MANUAL', 'AUDIENCE', 'DISTRIBUTION', 'EXPOSURE'));

-- On the publication: what the sponsor asked to buy while it was a draft, and the price per exposure frozen at its
-- submission (cleared when it goes back to its draft). The number bought is exposure_purchased (F-S13), set after the
-- deposit is.
ALTER TABLE qb_publication_version ADD COLUMN exposure_requested numeric(9, 0) CHECK (exposure_requested > 0);
ALTER TABLE qb_publication_version ADD COLUMN exposure_unit numeric(19, 4) CHECK (exposure_unit > 0);

-- The settlement counts a publication's successful exposures up to the end of a month: counted_at is the moment of the
-- success, so the month's edge is exact and the count does not scan the publication's whole table.
CREATE INDEX qb_exposure_counted_idx ON qb_exposure (publication_id, counted_at) WHERE counted_at IS NOT NULL;
