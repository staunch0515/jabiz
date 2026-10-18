-- Terms, privacy and help (docs/quizbuks/plans/Q7-messages-legal.md, Q7b, D-Q7-4 – D-Q7-6). All five are temporal and
-- only ever inserted into (platform docs/design/04-temporal-append-only.md, decision D9):
--  * qb_legal_doc_version: the editable draft of each kind of legal text (one instance per kind; the kind is unique
--    among current versions through the platform's check, decision D6), edited by the content administrators;
--  * qb_legal_version_version: a published version, written once (t.writeOnce(): one row per key, decision D29), unique per
--    kind and number. The version in effect at a time is the highest number whose effective_at is not after it;
--  * qb_legal_withdrawal_version: the withdrawal of a version announced for later, before it takes effect, written
--    once; a withdrawn version never takes effect and its number is not given again;
--  * qb_consent_version: a user's acceptance of one version, written once;
--  * qb_faq_entry_version: the help centre's questions and answers, edited by the content administrators.
-- Texts in several languages are jsonb ({language: text}, decision D20).

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

SELECT quizbuks_create_temporal_table('qb_legal_doc_version', 'doc_id', '
    kind              varchar(20)   NOT NULL,
    title             jsonb,
    body              jsonb,
    change_summary    jsonb,
    latest_version_no numeric(6, 0),
    last_published_at timestamptz');

SELECT quizbuks_create_temporal_table('qb_legal_version_version', 'legal_version_id', '
    kind             varchar(20)   NOT NULL,
    legal_version_no numeric(6, 0) NOT NULL CHECK (legal_version_no >= 1),
    title            jsonb         NOT NULL,
    body             jsonb         NOT NULL,
    change_summary   jsonb,
    requires_consent boolean       NOT NULL,
    published_at     timestamptz   NOT NULL,
    published_by     varchar(128)  NOT NULL,
    effective_at     timestamptz   NOT NULL,
    content_hash     varchar(64)   NOT NULL');

SELECT quizbuks_create_temporal_table('qb_legal_withdrawal_version', 'withdrawal_id', '
    legal_version_id uuid          NOT NULL REFERENCES entity_registry (entity_id),
    kind             varchar(20)   NOT NULL,
    legal_version_no numeric(6, 0) NOT NULL,
    withdrawn_at     timestamptz   NOT NULL,
    withdrawn_by     varchar(128)  NOT NULL,
    reason           varchar(1000) NOT NULL');

SELECT quizbuks_create_temporal_table('qb_consent_version', 'consent_id', '
    user_id          varchar(128)  NOT NULL,
    kind             varchar(20)   NOT NULL,
    legal_version_id uuid          NOT NULL REFERENCES entity_registry (entity_id),
    legal_version_no numeric(6, 0) NOT NULL,
    accepted_at      timestamptz   NOT NULL,
    entry            varchar(20),
    source           varchar(20)   NOT NULL');

SELECT quizbuks_create_temporal_table('qb_faq_entry_version', 'faq_id', '
    audience  varchar(10)   NOT NULL,
    category  varchar(20)   NOT NULL,
    question  jsonb         NOT NULL,
    answer    jsonb         NOT NULL,
    seq       numeric(6, 0) NOT NULL,
    published boolean       NOT NULL');

-- The draft is looked up by its kind (immutable, decision D29); the platform keeps it unique among current versions.
CREATE INDEX qb_legal_doc_version_kind_idx ON qb_legal_doc_version (kind);

-- Written once: one row per key (docs/design/04 section 5.4); one version per kind and number. The versions of a kind
-- are read by kind (every template and the publication), the consents by user (the gate and the pages).
CREATE UNIQUE INDEX qb_legal_version_version_once_idx ON qb_legal_version_version (legal_version_id);
CREATE UNIQUE INDEX qb_legal_version_version_no_idx ON qb_legal_version_version (kind, legal_version_no);
-- A version is withdrawn at most once; the withdrawals of a kind are read with its versions.
CREATE UNIQUE INDEX qb_legal_withdrawal_version_once_idx ON qb_legal_withdrawal_version (withdrawal_id);
CREATE UNIQUE INDEX qb_legal_withdrawal_version_version_idx ON qb_legal_withdrawal_version (legal_version_id);
CREATE INDEX qb_legal_withdrawal_version_kind_idx ON qb_legal_withdrawal_version (kind, legal_version_no);
CREATE UNIQUE INDEX qb_consent_version_once_idx ON qb_consent_version (consent_id);
CREATE INDEX qb_consent_version_user_idx ON qb_consent_version (user_id, kind, legal_version_no DESC);

-- The version of the sponsors' terms accepted with an application, part of what its review is bound to. Adding a
-- column rewrites nothing of the append-only table; earlier rows keep NULL.
ALTER TABLE qb_sponsor_version ADD COLUMN terms_version_no numeric(6, 0);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
