-- The uniqueness check of ItPrice finds its candidates through an index on the constraint's field (decision D29).
CREATE INDEX it_price_sku_idx ON it_price (sku);
