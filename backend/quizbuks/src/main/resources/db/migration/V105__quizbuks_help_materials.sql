-- F-C10b (docs/quizbuks/plans/impl/F-C10.md §6): the AI help's materials. V105 is a placeholder: the controller assigns
-- the number when the pull request is merged; one file.
--
--  * qb_help_manual_version: the usage manual of each entry (app: the Takers', sponsor: the Sponsors'), edited by the
--    content administrators in the management system (three languages, Markdown) and published by QB_HELP_PUBLISH,
--    which copies the draft into published_body. Temporal, like the legal texts' drafts: every change is a version
--    the platform keeps and audits. QB_SETUP creates the first drafts from the repository's texts (source_hash: the
--    hash of the repository's text last loaded into the draft, so that a startup check sees a newer one).
--  * qb_help_material: the snapshot of the materials of one entry and language (the published FAQ, terms and
--    privacy policy in effect, and the published manual) as a text file of the file policy qb.help.materials, given
--    to every qb.ai.help job of that entry and language as its file. Written by QB_HELP_MATERIALS_BUILD and the
--    outcomes of the job qb.help.materials only; an ordinary table (one row per entry and language, updated): the
--    latest build's size is what the startup check QB_HELP_MATERIALS compares with qb.help.manual-max-bytes.

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

SELECT quizbuks_create_temporal_table('qb_help_manual_version', 'manual_id', '
    entry          varchar(10) NOT NULL CHECK (entry IN (''app'', ''sponsor'')),
    body           jsonb,
    published_body jsonb,
    published_at   timestamptz,
    source_hash    varchar(64)');
-- The entry is unique among current versions (the platform's check); the setup and the processes find a manual by it.
CREATE INDEX qb_help_manual_version_entry_idx ON qb_help_manual_version (entry);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);

CREATE TABLE qb_help_material (
    material_id  varchar(36)   PRIMARY KEY,
    entry        varchar(10)   NOT NULL CHECK (entry IN ('app', 'sponsor')),
    language     varchar(5)    NOT NULL,
    -- The snapshot in use: its file (no foreign key, as every file field), hash, size and when its sources were read.
    file_id      uuid,
    content_hash varchar(64),
    bytes        numeric(9, 0) CHECK (bytes >= 0),
    built_at     timestamptz,
    -- The latest build: what it found, stored or not (too large: not stored, and the startup check fails).
    latest_hash  varchar(64)   NOT NULL,
    latest_bytes numeric(9, 0) NOT NULL CHECK (latest_bytes >= 0),
    latest_at    timestamptz   NOT NULL,
    -- A snapshot being stored (its job started and not yet ended).
    pending_hash varchar(64),
    pending_at   timestamptz,
    version      bigint        NOT NULL,
    CONSTRAINT uk_qb_help_material_language UNIQUE (entry, language)
);

-- F-C10b: of the help's input tokens, what the provider's prompt cache wrote and read (part of input_tokens).
ALTER TABLE qb_help_usage
    ADD COLUMN cache_write_tokens numeric(15, 0) NOT NULL DEFAULT 0 CHECK (cache_write_tokens >= 0),
    ADD COLUMN cache_read_tokens  numeric(15, 0) NOT NULL DEFAULT 0 CHECK (cache_read_tokens >= 0);
