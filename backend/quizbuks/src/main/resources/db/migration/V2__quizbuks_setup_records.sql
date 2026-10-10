-- What QB_SETUP has ever made or found (QbSetupRecord), so that it never adds back what an administrator removed.
-- Temporal and written once (platform docs/design/04-temporal-append-only.md sections 5.3-5.4, decision D29).

CREATE FUNCTION quizbuks_create_temporal_table(p_table text, p_key text, p_columns text) RETURNS void
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

SELECT quizbuks_create_temporal_table('qb_setup_record_version', 'record_id', '
    item_key varchar(300) NOT NULL');
-- Written once: one version per record.
CREATE UNIQUE INDEX qb_setup_record_version_once_idx ON qb_setup_record_version (record_id);
CREATE UNIQUE INDEX qb_setup_record_version_item_idx ON qb_setup_record_version (item_key);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
