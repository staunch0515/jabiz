-- Price: sample temporal entity (docs/design/04-temporal-append-only.md section 2.1). Only ever inserted into.
CREATE TABLE t_price (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    f_price_id        uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    f_sku             varchar(64)  NOT NULL,
    f_amount          numeric(19,0) NOT NULL,
    CONSTRAINT t_price_version_uk UNIQUE (f_price_id, version_no)
);
CREATE INDEX t_price_current_idx ON t_price (f_price_id, effect_start_time DESC, version_no DESC);
CREATE INDEX t_price_process_idx ON t_price (process_seq_id);
SELECT jabiz_protect_append_only('t_price');
