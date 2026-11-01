-- Content in several languages (docs/quizbuks/plans/Q73-content-languages.md, Q73a): one quiz carries the text of
-- several languages over a single structure. Every existing row is content of its base language only.
-- Columns are only added (sealed rows digest the columns they recorded; platform decision D58).

-- The quiz's languages besides its base (`language`), comma-separated; none when it has no translations.
ALTER TABLE qb_quiz_version ADD COLUMN translation_languages varchar(20);

-- A material in one of the quiz's other languages (§2.2b): a whole material of the same kind standing for the base
-- material `variant_of` in `language`. A base material has neither.
ALTER TABLE qb_material_version ADD COLUMN variant_of uuid;
ALTER TABLE qb_material_version ADD COLUMN language varchar(2);
CREATE INDEX qb_material_version_variant_idx ON qb_material_version (variant_of);

-- The languages of a version, base first, comma-separated; none for a version of its base language only.
ALTER TABLE qb_quiz_version_version ADD COLUMN languages varchar(20);

-- The translations of a quiz's text (§2.2): one row per language and element (`quiz.title`, `question:<id>.stem`,
-- `option:<id>.text`, or a language's own image `question:<id>.image`, `option:<id>.image`), with the hash of the
-- base text it translates (a changed base marks it out of date). Temporal, as all content; one live row per quiz,
-- language and element, which the processes keep under the quiz's lock.
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

SELECT quizbuks_create_temporal_table('qb_content_translation_version', 'translation_id', '
    quiz_id       uuid          NOT NULL,
    owner_id      varchar(128)  NOT NULL,
    language      varchar(2)    NOT NULL,
    element_key   varchar(64)   NOT NULL,
    text          varchar(5000),
    image         uuid,
    source_hash   varchar(16),
    translated_by varchar(8)    NOT NULL,
    ai_job_id     varchar(36)');

CREATE INDEX qb_content_translation_version_quiz_idx ON qb_content_translation_version (quiz_id, language);
CREATE INDEX qb_content_translation_version_owner_idx ON qb_content_translation_version (owner_id);
CREATE INDEX qb_content_translation_version_image_idx ON qb_content_translation_version (image);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
