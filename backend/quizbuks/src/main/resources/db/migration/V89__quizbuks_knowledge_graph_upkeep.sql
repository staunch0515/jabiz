-- F-S2b2: correcting and keeping up knowledge maps.
--
-- A lit record is revoked only after another member approves the request (qb_tree_lit_revoke_request, an ordinary
-- table: its state changes); the revocation itself is the append-only qb_tree_lit_revocation_version (V87). A version
-- waiting for review is reminded once (reminded_at).

CREATE TABLE qb_tree_lit_revoke_request (
    request_id          varchar(36)  PRIMARY KEY,
    lit_id              varchar(36)  NOT NULL,
    subject_kind        varchar(10)  NOT NULL CHECK (subject_kind IN ('USER', 'SPONSOR')),
    subject_id          varchar(128) NOT NULL,
    tree_id             varchar(36)  NOT NULL REFERENCES qb_tree (tree_id),
    node_id             varchar(36)  NOT NULL,
    operator_id         varchar(36),
    reason              varchar(500) NOT NULL,
    status              varchar(10)  NOT NULL CHECK (status IN ('REVIEW', 'REVOKED', 'REJECTED')),
    requested_by        varchar(128) NOT NULL,
    requested_at        timestamptz  NOT NULL,
    approval_request_id varchar(36),
    decided_at          timestamptz,
    reject_reason       varchar(500),
    version             bigint       NOT NULL
);
-- One request in review per lit record.
CREATE UNIQUE INDEX uk_qb_tree_lit_revoke_request_open ON qb_tree_lit_revoke_request (lit_id) WHERE status = 'REVIEW';
CREATE INDEX qb_tree_lit_revoke_request_subject_idx ON qb_tree_lit_revoke_request (subject_kind, subject_id);
CREATE INDEX qb_tree_lit_revoke_request_approval_idx ON qb_tree_lit_revoke_request (approval_request_id)
    WHERE approval_request_id IS NOT NULL;

ALTER TABLE qb_tree_version ADD COLUMN reminded_at timestamptz;
