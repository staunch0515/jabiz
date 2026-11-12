-- F-M6 (docs/quizbuks/first-launch-spec.md F-M6, docs/quizbuks/plans/impl/F-M.md §9): the distribution fee, charged
-- a month at a time for the people who started a publication's quiz, at a price per start frozen at its submission,
-- from a deposit set aside then (account 2180, purpose DISTRIBUTION).

-- An operator's price (an ordinary table: one row an operator, saved over in turns; no row = no distribution fee).
CREATE TABLE qb_distribution_price (
    price_id        varchar(36)    PRIMARY KEY,
    operator_id     varchar(36)    NOT NULL UNIQUE,
    rate            numeric(19, 4) NOT NULL CHECK (rate >= 0),
    unit_floor      numeric(19, 4) NOT NULL CHECK (unit_floor >= 0),
    unit_ceiling    numeric(19, 4) NOT NULL CHECK (unit_ceiling >= 0),
    minimum_deposit numeric(19, 0) NOT NULL CHECK (minimum_deposit >= 0),
    reason          varchar(500)   NOT NULL,
    updated_at      timestamptz    NOT NULL,
    updated_by      varchar(128)   NOT NULL,
    version         bigint         NOT NULL,
    CONSTRAINT qb_distribution_price_range CHECK (unit_floor <= unit_ceiling)
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

-- One settlement a publication and month (written once). The amounts are cumulative: the amount of a row is what the
-- starts up to it come to, rounded up, less what the rows before it charged.
SELECT quizbuks_create_temporal_table('qb_distribution_settlement_version', 'settlement_id', '
    publication_id    varchar(36)    NOT NULL,
    sponsor_id        varchar(128)   NOT NULL,
    operator_id       varchar(36),
    period            varchar(7)     NOT NULL CHECK (period ~ ''^[0-9]{4}-(0[1-9]|1[0-2])$''),
    from_starts       bigint         NOT NULL CHECK (from_starts >= 0),
    to_starts         bigint         NOT NULL,
    starts            bigint         NOT NULL CHECK (starts >= 0),
    unit              numeric(19, 4) NOT NULL CHECK (unit >= 0),
    at_floor          boolean        NOT NULL,
    cumulative_amount numeric(19, 0) NOT NULL CHECK (cumulative_amount >= 0),
    amount            numeric(19, 0) NOT NULL CHECK (amount >= 0),
    tax               numeric(19, 0) NOT NULL CHECK (tax >= 0),
    total             numeric(19, 0) NOT NULL CHECK (total >= 0),
    final             boolean        NOT NULL,
    charge_id         varchar(36),
    settled_at        timestamptz    NOT NULL,
    CONSTRAINT qb_distribution_settlement_starts CHECK (to_starts = from_starts + starts),
    CONSTRAINT qb_distribution_settlement_charge CHECK ((amount = 0) = (charge_id IS NULL))');

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);

-- Written once: one row per key.
CREATE UNIQUE INDEX qb_distribution_settlement_version_once_idx ON qb_distribution_settlement_version (settlement_id);
-- One row a publication and month; one final row a publication (the monthly and the final job never both write).
CREATE UNIQUE INDEX qb_distribution_settlement_version_period_idx
    ON qb_distribution_settlement_version (publication_id, period);
CREATE UNIQUE INDEX qb_distribution_settlement_version_final_idx
    ON qb_distribution_settlement_version (publication_id) WHERE final;
CREATE INDEX qb_distribution_settlement_version_sponsor_idx
    ON qb_distribution_settlement_version (sponsor_id, period);
CREATE INDEX qb_distribution_settlement_version_period_only_idx ON qb_distribution_settlement_version (period);

-- The distribution fee is a kind of charge.
ALTER TABLE qb_fee_charge_version DROP CONSTRAINT qb_fee_charge_version_kind_check;
ALTER TABLE qb_fee_charge_version ADD CONSTRAINT qb_fee_charge_version_kind_check
    CHECK (kind IN ('MONTHLY', 'MANUAL', 'AUDIENCE', 'DISTRIBUTION'));

-- On the publication, frozen at its submission (cleared when it goes back to its draft): the price per start, and
-- whether it is the operator's floor (the tiers' value was too low to set it).
ALTER TABLE qb_publication_version ADD COLUMN distribution_unit numeric(19, 4);
ALTER TABLE qb_publication_version ADD COLUMN distribution_at_floor boolean;
