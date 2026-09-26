-- Carrier: sample temporal entity of ROADMAP phase 10 (docs/design/12-frontend.md). Only ever inserted into
-- (docs/design/04-temporal-append-only.md section 2.1).
CREATE TABLE carrier_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    carrier_id        uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    carrier_code      varchar(10)   NOT NULL,
    carrier_name      varchar(100)  NOT NULL,
    country_code      varchar(2)    NOT NULL,
    credit_limit      numeric(19,0) NOT NULL,
    contact_email     varchar(200),
    active            boolean       NOT NULL,
    CONSTRAINT carrier_version_uk UNIQUE (carrier_id, version_no)
);
CREATE INDEX carrier_version_current_idx ON carrier_version (carrier_id, effect_start_time DESC, version_no DESC);
CREATE INDEX carrier_version_process_idx ON carrier_version (process_seq_id);
CREATE INDEX carrier_version_code_idx ON carrier_version (carrier_code);
SELECT jabiz_protect_append_only('carrier_version');
