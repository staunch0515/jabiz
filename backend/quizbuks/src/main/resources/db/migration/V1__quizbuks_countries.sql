-- The country dictionary QbCountry (docs/quizbuks/02-design.md section 3.1): every ISO 3166-1 country with its name
-- in three languages and the regions it belongs to. Temporal: only ever inserted into (platform
-- docs/design/04-temporal-append-only.md, decision D9).

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

-- regions: the region codes joined by commas in a fixed order (Regions.java), always with GLOBAL.
SELECT quizbuks_create_temporal_table('qb_country_version', 'country_id', '
    code    varchar(2)  NOT NULL,
    name    jsonb       NOT NULL,
    regions varchar(64) NOT NULL');
-- The code is unique and immutable: lookups by it (and the unique check) use this index.
CREATE INDEX qb_country_version_code_idx ON qb_country_version (code);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
