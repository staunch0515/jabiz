-- The startup check of sealed columns (decision D58; docs/design/21-audit-retention.md section 2.1) reads each
-- distinct list of columns the blocks recorded, skipping on this index from one (table, list) to the next; the
-- seal number makes the first entry of each the first block that recorded it. An index leaves the append-only
-- protection of the table as it is.
CREATE INDEX sys_integrity_seal_table_columns_idx ON sys_integrity_seal_table (table_name, columns, seal_no);
