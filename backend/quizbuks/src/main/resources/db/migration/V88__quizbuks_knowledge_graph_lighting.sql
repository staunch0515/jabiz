-- F-S2b1: publications bound to a node of a knowledge graph, and the nodes lit.
--
-- A draft names the node it is bound to (the graph and the node: node ids are unique within a graph); the card
-- freezes it when the publication goes on. A node's row also keeps the condition in effect, and qb_tree_node_ref
-- which nodes that condition names, so that lighting a node evaluates the nodes depending on it. Lit records and
-- their revocations are written once (append-only); a revocation carries the subject again, so that a start reads a
-- person's lit records and revocations in one statement.

ALTER TABLE qb_publication_version ADD COLUMN tree_id varchar(36);
ALTER TABLE qb_publication_version ADD COLUMN tree_node_id varchar(36);
ALTER TABLE qb_publication_version ADD CONSTRAINT qb_publication_version_tree_node_check
    CHECK ((tree_id IS NULL) = (tree_node_id IS NULL));

ALTER TABLE qb_publication_card ADD COLUMN tree_id varchar(36);
ALTER TABLE qb_publication_card ADD COLUMN tree_node_id varchar(36);
ALTER TABLE qb_publication_card ADD CONSTRAINT qb_publication_card_tree_node_check
    CHECK ((tree_id IS NULL) = (tree_node_id IS NULL));
-- The publications bound to a node (lighting, and the check of a new version's nodes).
CREATE INDEX qb_publication_card_tree_node_idx ON qb_publication_card (tree_id, tree_node_id)
    WHERE tree_node_id IS NOT NULL;

ALTER TABLE qb_tree_node ADD COLUMN condition text;

CREATE TABLE qb_tree_node_ref (
    row_id      varchar(36) PRIMARY KEY,
    tree_id     varchar(36) NOT NULL REFERENCES qb_tree (tree_id),
    node_id     varchar(36) NOT NULL,
    ref_node_id varchar(36) NOT NULL,
    version     bigint      NOT NULL,
    CONSTRAINT uk_qb_tree_node_ref UNIQUE (tree_id, node_id, ref_node_id)
);
-- The nodes whose condition names a node just lit.
CREATE INDEX qb_tree_node_ref_ref_idx ON qb_tree_node_ref (ref_node_id);

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

SELECT quizbuks_create_temporal_table('qb_tree_lit_version', 'lit_id', '
    subject_kind varchar(10)  NOT NULL CHECK (subject_kind IN (''USER'', ''SPONSOR'')),
    subject_id   varchar(128) NOT NULL,
    tree_id      varchar(36)  NOT NULL,
    node_id      varchar(36)  NOT NULL,
    lit_at       timestamptz  NOT NULL,
    basis        text');
CREATE UNIQUE INDEX qb_tree_lit_version_once_idx ON qb_tree_lit_version (lit_id);
CREATE INDEX qb_tree_lit_version_subject_idx ON qb_tree_lit_version (subject_kind, subject_id);
CREATE INDEX qb_tree_lit_version_node_idx ON qb_tree_lit_version (tree_id, node_id);

SELECT quizbuks_create_temporal_table('qb_tree_lit_revocation_version', 'revocation_id', '
    lit_id              varchar(36)  NOT NULL,
    subject_kind        varchar(10)  NOT NULL CHECK (subject_kind IN (''USER'', ''SPONSOR'')),
    subject_id          varchar(128) NOT NULL,
    reason              varchar(500) NOT NULL,
    requested_by        varchar(128) NOT NULL,
    approval_request_id varchar(36),
    revoked_at          timestamptz  NOT NULL');
CREATE UNIQUE INDEX qb_tree_lit_revocation_version_once_idx ON qb_tree_lit_revocation_version (revocation_id);
-- A lit record is revoked at most once.
CREATE UNIQUE INDEX qb_tree_lit_revocation_version_lit_idx ON qb_tree_lit_revocation_version (lit_id);
CREATE INDEX qb_tree_lit_revocation_version_subject_idx ON qb_tree_lit_revocation_version (subject_kind, subject_id);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
