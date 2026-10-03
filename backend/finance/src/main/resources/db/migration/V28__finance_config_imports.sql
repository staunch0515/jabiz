-- Configuration promotion (docs/finance/ROADMAP.md F10d; FIN-SC-005): a configuration package proposed in this
-- environment, its differences, and who published or withdrew it when.

CREATE TABLE fi_config_import_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_id         uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    source            varchar(100)  NOT NULL,
    exported_at       timestamptz,
    package_hash      varchar(64)   NOT NULL,
    package_text      text          NOT NULL,
    differences       text          NOT NULL,
    changes           numeric(9, 0) NOT NULL,
    status            varchar(10)   NOT NULL,
    proposed_by       varchar(100)  NOT NULL,
    proposed_at       timestamptz   NOT NULL,
    published_by      varchar(100),
    published_at      timestamptz,
    withdrawn_by      varchar(100),
    withdrawn_at      timestamptz,
    CONSTRAINT fi_config_import_version_uk UNIQUE (import_id, version_no));
CREATE INDEX fi_config_import_version_current_idx
    ON fi_config_import_version (import_id, effect_start_time DESC, version_no DESC);
CREATE INDEX fi_config_import_version_process_idx ON fi_config_import_version (process_seq_id);
CREATE INDEX fi_config_import_version_hash_idx ON fi_config_import_version (package_hash);
SELECT jabiz_protect_append_only('fi_config_import_version'::regclass);
