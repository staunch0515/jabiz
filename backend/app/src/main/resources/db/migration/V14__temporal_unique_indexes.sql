-- Indexes for the uniqueness checks of the demo's temporal entities (decision D29): the check finds the instances
-- that have used the values through them.
CREATE INDEX t_price_sku_idx ON t_price (f_sku);
CREATE INDEX sales_order_line_version_line_idx ON sales_order_line_version (order_id, line_no);
