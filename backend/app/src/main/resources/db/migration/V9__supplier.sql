-- Supplier: the example object of docs/guide/new-business-object.md. A temporal table, only ever inserted into
-- (docs/design/04-temporal-append-only.md section 2.1): system columns, the version constraint, the lookup indexes and
-- the trigger that rejects UPDATE, DELETE and TRUNCATE.
CREATE TABLE supplier_version (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplier_id       uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    supplier_code     varchar(10)  NOT NULL,
    supplier_name     varchar(100) NOT NULL,
    country_code      varchar(2)   NOT NULL,
    lead_time_days    numeric(3,0) NOT NULL,
    active            boolean      NOT NULL,
    CONSTRAINT supplier_version_uk UNIQUE (supplier_id, version_no)
);
CREATE INDEX supplier_version_current_idx ON supplier_version (supplier_id, effect_start_time DESC, version_no DESC);
CREATE INDEX supplier_version_process_idx ON supplier_version (process_seq_id);
CREATE INDEX supplier_version_code_idx ON supplier_version (supplier_code);
SELECT jabiz_protect_append_only('supplier_version');
