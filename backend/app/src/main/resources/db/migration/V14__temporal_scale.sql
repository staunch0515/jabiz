-- Temporal data at volume in the demo (decision D29).

-- Indexes for the uniqueness checks of temporal entities: the check finds the instances that have used the values
-- through them.
CREATE INDEX t_price_sku_idx ON t_price (f_sku);
CREATE INDEX sales_order_line_version_line_idx ON sales_order_line_version (order_id, line_no);

-- Goods receipts, written once (StockReceipt): the key alone is unique.
CREATE TABLE stock_receipt_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    receipt_id        uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    stock_level_id    uuid          NOT NULL REFERENCES entity_registry (entity_id),
    quantity          numeric(12,0) NOT NULL,
    note              varchar(200),
    CONSTRAINT stock_receipt_version_uk UNIQUE (receipt_id, version_no)
);
CREATE UNIQUE INDEX stock_receipt_version_once_uk ON stock_receipt_version (receipt_id);
CREATE INDEX stock_receipt_version_current_idx ON stock_receipt_version
    (receipt_id, effect_start_time DESC, version_no DESC);
CREATE INDEX stock_receipt_version_process_idx ON stock_receipt_version (process_seq_id);
CREATE INDEX stock_receipt_version_stock_idx ON stock_receipt_version (stock_level_id);
SELECT jabiz_protect_append_only('stock_receipt_version');
