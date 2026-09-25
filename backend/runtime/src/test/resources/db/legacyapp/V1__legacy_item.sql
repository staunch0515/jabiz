-- Application script used by PlatformSchemaMigrationTest; records whether platform objects already existed.
CREATE TABLE legacy_item (id integer PRIMARY KEY);
CREATE TABLE legacy_item_meta AS
    SELECT count(*) AS sequence_seen FROM pg_sequences
    WHERE sequencename = 'op_process_seq' AND schemaname = current_schema();
