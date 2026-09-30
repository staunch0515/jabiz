-- Saved import mappings (docs/design/20-imports.md section 4, decision D26): how the columns of files of one layout
-- feed an import's fields, under a name. The temporal platform entity SysImportMapping; versions are only inserted
-- (decision D5), so what a mapping said when a file was imported stays readable. The mapping is JSON text.
CREATE TABLE sys_import_mapping_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mapping_id        uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    import_id         varchar(100)  NOT NULL,
    mapping_name      varchar(100)  NOT NULL,
    mapping           varchar(8000) NOT NULL,
    CONSTRAINT sys_import_mapping_version_uk UNIQUE (mapping_id, version_no)
);
CREATE INDEX sys_import_mapping_version_current_idx
    ON sys_import_mapping_version (mapping_id, effect_start_time DESC, version_no DESC);
CREATE INDEX sys_import_mapping_version_process_idx ON sys_import_mapping_version (process_seq_id);
CREATE INDEX sys_import_mapping_version_import_idx ON sys_import_mapping_version (import_id, mapping_name);
SELECT jabiz_protect_append_only('sys_import_mapping_version');
