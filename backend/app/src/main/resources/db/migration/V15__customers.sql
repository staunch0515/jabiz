-- Customers of the orders sample, kept by administrators (docs/design/18-numbering-approvals-tasks.md section 5.6,
-- ROADMAP phase 16a): a customer may have an account, the user who hears of the shipment of its orders by mail. The
-- recipient comes from this master, not from whoever places an order. Orders still name their customer by code; a code
-- without a customer here simply has no account.
CREATE TABLE customer_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id       uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    customer_code     varchar(30)   NOT NULL,
    customer_name     varchar(100)  NOT NULL,
    user_id           uuid          REFERENCES entity_registry (entity_id),
    CONSTRAINT customer_version_uk UNIQUE (customer_id, version_no)
);
CREATE INDEX customer_version_current_idx ON customer_version (customer_id, effect_start_time DESC, version_no DESC);
CREATE INDEX customer_version_process_idx ON customer_version (process_seq_id);
CREATE INDEX customer_version_code_idx ON customer_version (customer_code);
SELECT jabiz_protect_append_only('customer_version');
