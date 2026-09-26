-- Orders and inventory, the demonstration domain of ROADMAP phase 11 (app com.jabiz.app.commerce). Every table is
-- temporal and only ever inserted into (docs/design/04-temporal-append-only.md section 2.1, decision D5).

CREATE FUNCTION commerce_create_temporal_table(p_table text, p_key text, p_columns text) RETURNS void
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

SELECT commerce_create_temporal_table('product_version', 'product_id', '
    sku          varchar(30)   NOT NULL,
    product_name varchar(200)  NOT NULL,
    unit_price   numeric(19,0) NOT NULL,
    active       boolean       NOT NULL');
CREATE INDEX product_version_sku_idx ON product_version (sku);

SELECT commerce_create_temporal_table('warehouse_version', 'warehouse_id', '
    warehouse_code varchar(10)  NOT NULL,
    warehouse_name varchar(100) NOT NULL,
    active         boolean      NOT NULL');
CREATE INDEX warehouse_version_code_idx ON warehouse_version (warehouse_code);

SELECT commerce_create_temporal_table('stock_level_version', 'stock_level_id', '
    warehouse_id uuid          NOT NULL REFERENCES entity_registry (entity_id),
    product_id   uuid          NOT NULL REFERENCES entity_registry (entity_id),
    on_hand      numeric(12,0) NOT NULL,
    reserved     numeric(12,0) NOT NULL');
CREATE INDEX stock_level_version_item_idx ON stock_level_version (warehouse_id, product_id);
CREATE INDEX stock_level_version_product_idx ON stock_level_version (product_id);

SELECT commerce_create_temporal_table('sales_order_version', 'order_id', '
    order_no      varchar(30)   NOT NULL,
    customer_code varchar(30)   NOT NULL,
    warehouse_id  uuid          NOT NULL REFERENCES entity_registry (entity_id),
    ordered_time  timestamptz   NOT NULL,
    status        varchar(20)   NOT NULL,
    total_amount  numeric(19,0) NOT NULL,
    shipped_time  timestamptz,
    waybill_id    varchar(64)   REFERENCES t_legacy_waybill_2026 (f_wb_sn)');
CREATE INDEX sales_order_version_no_idx ON sales_order_version (order_no);
CREATE INDEX sales_order_version_customer_idx ON sales_order_version (customer_code);
CREATE INDEX sales_order_version_ordered_idx ON sales_order_version (ordered_time);

SELECT commerce_create_temporal_table('sales_order_line_version', 'order_line_id', '
    order_id    uuid          NOT NULL REFERENCES entity_registry (entity_id),
    line_no     integer       NOT NULL,
    product_id  uuid          NOT NULL REFERENCES entity_registry (entity_id),
    quantity    numeric(9,0)  NOT NULL,
    unit_price  numeric(19,0) NOT NULL,
    line_amount numeric(19,0) NOT NULL');
CREATE INDEX sales_order_line_version_order_idx ON sales_order_line_version (order_id);
CREATE INDEX sales_order_line_version_product_idx ON sales_order_line_version (product_id);

DROP FUNCTION commerce_create_temporal_table(text, text, text);
