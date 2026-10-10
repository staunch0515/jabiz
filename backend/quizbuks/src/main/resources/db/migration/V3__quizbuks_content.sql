-- Quiz content (docs/quizbuks/02-design.md section 3.2, docs/quizbuks/plans/Q3-content.md): quizzes, their
-- materials with image groups, questions with options, versions (written once) and the files the versions keep.
-- Temporal: only ever inserted into (platform docs/design/04-temporal-append-only.md, decision D9).

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

-- Text lengths are those of content.ContentLimits; file columns hold fileIds (no foreign key, platform 14 section 4).
SELECT quizbuks_create_temporal_table('qb_quiz_version', 'quiz_id', '
    owner_id              varchar(128)  NOT NULL,
    title                 varchar(100)  NOT NULL,
    intro                 varchar(5000),
    cover                 uuid,
    time_limit_sec        numeric(5, 0),
    status                varchar(20)   NOT NULL,
    removed               boolean       NOT NULL,
    ai_generated          boolean       NOT NULL,
    latest_version_no     numeric(6, 0) NOT NULL,
    version_label         varchar(20),
    revision              numeric(9, 0) NOT NULL,
    edited_at             timestamptz   NOT NULL,
    changed_since_version boolean       NOT NULL,
    question_count        numeric(4, 0) NOT NULL,
    material_count        numeric(4, 0) NOT NULL,
    cloned_from           uuid');

SELECT quizbuks_create_temporal_table('qb_material_version', 'material_id', '
    quiz_id     uuid          NOT NULL,
    owner_id    varchar(128)  NOT NULL,
    seq         numeric(4, 0) NOT NULL,
    kind        varchar(20)   NOT NULL,
    title       varchar(100),
    description varchar(500),
    body        varchar(20000),
    url         varchar(2000),
    pdf         uuid,
    audio       uuid');

SELECT quizbuks_create_temporal_table('qb_material_image_version', 'image_id', '
    material_id uuid          NOT NULL,
    quiz_id     uuid          NOT NULL,
    owner_id    varchar(128)  NOT NULL,
    seq         numeric(4, 0) NOT NULL,
    image       uuid          NOT NULL,
    caption     varchar(200)');

SELECT quizbuks_create_temporal_table('qb_question_version', 'question_id', '
    quiz_id  uuid          NOT NULL,
    owner_id varchar(128)  NOT NULL,
    seq      numeric(4, 0) NOT NULL,
    stem     varchar(1000),
    image    uuid,
    points   numeric(4, 0) NOT NULL');

SELECT quizbuks_create_temporal_table('qb_option_version', 'option_id', '
    question_id uuid          NOT NULL,
    quiz_id     uuid          NOT NULL,
    owner_id    varchar(128)  NOT NULL,
    seq         numeric(4, 0) NOT NULL,
    text        varchar(300),
    image       uuid,
    correct     boolean       NOT NULL');

-- The version number is quiz_version_no: version_no is the row version of every temporal table.
SELECT quizbuks_create_temporal_table('qb_quiz_version_version', 'version_id', '
    quiz_id         uuid          NOT NULL,
    owner_id        varchar(128)  NOT NULL,
    quiz_version_no numeric(6, 0) NOT NULL,
    label           varchar(20)   NOT NULL,
    title           varchar(100)  NOT NULL,
    time_limit_sec  numeric(5, 0),
    question_count  numeric(4, 0) NOT NULL,
    material_count  numeric(4, 0) NOT NULL,
    full_score      numeric(7, 0) NOT NULL,
    content         text          NOT NULL,
    content_hash    char(64)      NOT NULL,
    versioned_at    timestamptz   NOT NULL');

SELECT quizbuks_create_temporal_table('qb_version_file_version', 'version_file_id', '
    quiz_id          uuid          NOT NULL,
    owner_id         varchar(128)  NOT NULL,
    first_version_no numeric(6, 0) NOT NULL,
    image            uuid,
    pdf              uuid,
    audio            uuid');

-- Parents and owners: the processes read a quiz's parts, the sponsor datasets a sponsor's rows.
CREATE INDEX qb_quiz_version_owner_idx ON qb_quiz_version (owner_id);
CREATE INDEX qb_quiz_version_cloned_idx ON qb_quiz_version (cloned_from);
CREATE INDEX qb_material_version_quiz_idx ON qb_material_version (quiz_id);
CREATE INDEX qb_material_version_owner_idx ON qb_material_version (owner_id);
CREATE INDEX qb_material_image_version_material_idx ON qb_material_image_version (material_id);
CREATE INDEX qb_material_image_version_quiz_idx ON qb_material_image_version (quiz_id);
CREATE INDEX qb_material_image_version_owner_idx ON qb_material_image_version (owner_id);
CREATE INDEX qb_question_version_quiz_idx ON qb_question_version (quiz_id);
CREATE INDEX qb_question_version_owner_idx ON qb_question_version (owner_id);
CREATE INDEX qb_option_version_question_idx ON qb_option_version (question_id);
CREATE INDEX qb_option_version_quiz_idx ON qb_option_version (quiz_id);
CREATE INDEX qb_option_version_owner_idx ON qb_option_version (owner_id);
CREATE INDEX qb_quiz_version_version_owner_idx ON qb_quiz_version_version (owner_id);
CREATE INDEX qb_version_file_version_quiz_idx ON qb_version_file_version (quiz_id);
CREATE INDEX qb_version_file_version_owner_idx ON qb_version_file_version (owner_id);

-- Every file column: the platform's sweep looks up each file field with = ANY(:ids) every day.
CREATE INDEX qb_quiz_version_cover_idx ON qb_quiz_version (cover);
CREATE INDEX qb_material_version_pdf_idx ON qb_material_version (pdf);
CREATE INDEX qb_material_version_audio_idx ON qb_material_version (audio);
CREATE INDEX qb_material_image_version_image_idx ON qb_material_image_version (image);
CREATE INDEX qb_question_version_image_idx ON qb_question_version (image);
CREATE INDEX qb_option_version_image_idx ON qb_option_version (image);
CREATE INDEX qb_version_file_version_image_idx ON qb_version_file_version (image);
CREATE INDEX qb_version_file_version_pdf_idx ON qb_version_file_version (pdf);
CREATE INDEX qb_version_file_version_audio_idx ON qb_version_file_version (audio);

-- Versions are written once: one row per version, and one version per number of a quiz.
CREATE UNIQUE INDEX qb_quiz_version_version_once_idx ON qb_quiz_version_version (version_id);
CREATE UNIQUE INDEX qb_quiz_version_version_no_idx ON qb_quiz_version_version (quiz_id, quiz_version_no);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
