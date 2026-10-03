-- A period's balances (docs/finance/perf.md Q1; ROADMAP F9 decision D1): each account's debits and credits of the
-- period by department and location, counted from the entries recorded up to a time. Written once by the close, the
-- year close and FIN_PERIOD_BALANCE_SNAPSHOT (for periods closed before F9); the reports read the latest snapshot of a
-- period plus the entries recorded after it.

CREATE TABLE fi_period_balance_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    balance_id        uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    period_key        varchar(7)    NOT NULL,
    fiscal_year       numeric(4,0)  NOT NULL,
    period_no         numeric(2,0)  NOT NULL,
    first_booking     timestamptz   NOT NULL,
    last_booking      timestamptz   NOT NULL,
    account_code      varchar(20)   NOT NULL,
    department        varchar(100),
    location          varchar(100),
    debit             numeric(17,2) NOT NULL,
    credit            numeric(17,2) NOT NULL,
    counted_to        timestamptz   NOT NULL,
    CONSTRAINT fi_period_balance_version_uk UNIQUE (balance_id, version_no));
CREATE INDEX fi_period_balance_version_current_idx
    ON fi_period_balance_version (balance_id, effect_start_time DESC, version_no DESC);
CREATE INDEX fi_period_balance_version_process_idx ON fi_period_balance_version (process_seq_id);
CREATE UNIQUE INDEX fi_period_balance_version_once_uk ON fi_period_balance_version (balance_id);
-- A report finds a period's latest snapshot, then its rows.
CREATE INDEX fi_period_balance_version_period_idx ON fi_period_balance_version (period_key, counted_to);
-- One snapshot per period and count: two taken at the same time would be read twice.
CREATE UNIQUE INDEX fi_period_balance_version_row_uk ON fi_period_balance_version
    (period_key, counted_to, account_code, department, location) NULLS NOT DISTINCT;
SELECT jabiz_protect_append_only('fi_period_balance_version'::regclass);
