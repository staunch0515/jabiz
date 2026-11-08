-- F-S2a (docs/quizbuks/plans/impl/F-S2.md §2): knowledge graphs, their versions, their nodes and each version's
-- definition of each node. Ordinary tables written by the QB_TREE_* processes only (their history is the platform's
-- audit); a version in effect is never changed again. A node keeps its id across versions, and an operator's copy of
-- a template keeps the template's node ids: node ids are unique within a graph, not across graphs.
CREATE TABLE qb_tree (
    tree_id             varchar(36)   PRIMARY KEY,
    owner_kind          varchar(10)   NOT NULL,
    owner_id            varchar(128),
    subject_kind        varchar(10)   NOT NULL,
    template_tree_id    varchar(36),
    template_version_no numeric(6, 0),
    current_version_id  varchar(36),
    status              varchar(10)   NOT NULL,
    created_at          timestamptz   NOT NULL,
    created_by          varchar(128)  NOT NULL,
    retired_at          timestamptz,
    revision            numeric(9, 0) NOT NULL,
    version             bigint        NOT NULL,
    CONSTRAINT qb_tree_owner_check CHECK (owner_kind IN ('PLATFORM', 'OPERATOR', 'SPONSOR')),
    CONSTRAINT qb_tree_owner_id_check CHECK ((owner_kind = 'PLATFORM') = (owner_id IS NULL)),
    CONSTRAINT qb_tree_subject_check CHECK (subject_kind IN ('TAKER', 'SPONSOR')),
    -- A Sponsor's graph is lit by Takers; an operator's is a copy of a template.
    CONSTRAINT qb_tree_sponsor_subject_check CHECK (owner_kind <> 'SPONSOR' OR subject_kind = 'TAKER'),
    CONSTRAINT qb_tree_copy_check CHECK ((owner_kind = 'OPERATOR') = (template_tree_id IS NOT NULL)),
    CONSTRAINT qb_tree_status_check CHECK (status IN ('ACTIVE', 'RETIRED'))
);
-- An owner's graphs (the Sponsor's limit, an operator's copies), and the copies of a template.
CREATE INDEX qb_tree_owner_idx ON qb_tree (owner_kind, owner_id);
CREATE INDEX qb_tree_template_idx ON qb_tree (template_tree_id) WHERE template_tree_id IS NOT NULL;

CREATE TABLE qb_tree_version (
    version_id          varchar(36)   PRIMARY KEY,
    tree_id             varchar(36)   NOT NULL REFERENCES qb_tree (tree_id),
    version_no          numeric(6, 0) NOT NULL,
    name                jsonb         NOT NULL,
    description         jsonb,
    status              varchar(10)   NOT NULL,
    content_hash        varchar(64),
    approval_request_id varchar(64),
    submitted_at        timestamptz,
    submitted_by        varchar(128),
    decided_at          timestamptz,
    reject_reason       text,
    edited_at           timestamptz   NOT NULL,
    version             bigint        NOT NULL,
    CONSTRAINT uk_qb_tree_version_no UNIQUE (tree_id, version_no),
    CONSTRAINT qb_tree_version_status_check
        CHECK (status IN ('DRAFT', 'REVIEW', 'ACTIVE', 'SUPERSEDED', 'REJECTED'))
);
-- At most one version of a graph being worked on (a draft or one in review) and one in effect.
CREATE UNIQUE INDEX uk_qb_tree_version_open ON qb_tree_version (tree_id) WHERE status IN ('DRAFT', 'REVIEW');
CREATE UNIQUE INDEX uk_qb_tree_version_active ON qb_tree_version (tree_id) WHERE status = 'ACTIVE';
CREATE INDEX qb_tree_version_request_idx ON qb_tree_version (approval_request_id)
    WHERE approval_request_id IS NOT NULL;

CREATE TABLE qb_tree_node (
    row_id             varchar(36)   PRIMARY KEY,
    tree_id            varchar(36)   NOT NULL REFERENCES qb_tree (tree_id),
    node_id            varchar(36)   NOT NULL,
    created_in_version numeric(6, 0) NOT NULL,
    active             boolean       NOT NULL,
    before_answer      text,
    bindable           boolean       NOT NULL,
    version            bigint        NOT NULL,
    CONSTRAINT uk_qb_tree_node UNIQUE (tree_id, node_id)
);
-- A node by its id, in whichever graph (a lit record names the node, F-S2b).
CREATE INDEX qb_tree_node_node_idx ON qb_tree_node (node_id);

CREATE TABLE qb_tree_node_def (
    row_id      varchar(36)   PRIMARY KEY,
    version_id  varchar(36)   NOT NULL REFERENCES qb_tree_version (version_id),
    node_id     varchar(36)   NOT NULL,
    name        jsonb         NOT NULL,
    description jsonb,
    condition   text          NOT NULL,
    seq         numeric(4, 0) NOT NULL,
    version     bigint        NOT NULL,
    CONSTRAINT uk_qb_tree_node_def UNIQUE (version_id, node_id)
);
