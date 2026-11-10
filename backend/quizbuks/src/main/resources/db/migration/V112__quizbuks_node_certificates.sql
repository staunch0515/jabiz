-- F-S5a (docs/quizbuks/plans/impl/F-S5.md §2.5): the certificate a node's lighting issues. A node's definition names it
-- (certificate_names, a text by language, none for no certificate), the node row of the version in effect carries it, and
-- the lit record freezes it when lit (an append-only table: a column added, nothing renamed or changed).
ALTER TABLE qb_tree_node_def ADD COLUMN certificate_names jsonb;
ALTER TABLE qb_tree_node ADD COLUMN certificate_names jsonb;
ALTER TABLE qb_tree_lit_version ADD COLUMN certificate_names jsonb;

-- A certificate is now of one of two sources: Q77's (a certification held, with its end) or a node's (issued by the
-- lighting, with no end: A2-12). A node's is frozen as lit (the holder's name, the names, the issuer); nothing is
-- backfilled. The PDF is issued by the holder on the first download, the run's id kept here.
ALTER TABLE qb_certificate ALTER COLUMN certification DROP NOT NULL;
ALTER TABLE qb_certificate ALTER COLUMN certification_version DROP NOT NULL;
ALTER TABLE qb_certificate ALTER COLUMN valid_until DROP NOT NULL;
ALTER TABLE qb_certificate ALTER COLUMN language DROP NOT NULL;
ALTER TABLE qb_certificate ADD COLUMN source varchar(16) NOT NULL DEFAULT 'CERTIFICATION';
ALTER TABLE qb_certificate ADD COLUMN subject_kind varchar(10) NOT NULL DEFAULT 'USER';
ALTER TABLE qb_certificate ADD COLUMN lit_id varchar(36);
ALTER TABLE qb_certificate ADD COLUMN tree_id varchar(36);
ALTER TABLE qb_certificate ADD COLUMN node_id varchar(36);
ALTER TABLE qb_certificate ADD COLUMN certificate_names jsonb;
ALTER TABLE qb_certificate ADD COLUMN issuer_name varchar(200);
ALTER TABLE qb_certificate ADD COLUMN shared boolean NOT NULL DEFAULT true;
ALTER TABLE qb_certificate ADD COLUMN run_id varchar(36);
-- One certificate a lighting (unset for Q77's, which a unique constraint does not compare): the issue is exactly once.
ALTER TABLE qb_certificate ADD CONSTRAINT uk_qb_certificate_lit UNIQUE (lit_id);
ALTER TABLE qb_certificate ADD CONSTRAINT ck_qb_certificate_source CHECK (
    subject_kind IN ('USER', 'SPONSOR') AND (
        (source = 'NODE' AND lit_id IS NOT NULL AND tree_id IS NOT NULL AND node_id IS NOT NULL
            AND certificate_names IS NOT NULL AND issuer_name IS NOT NULL AND valid_until IS NULL)
        OR (source = 'CERTIFICATION' AND subject_kind = 'USER' AND certification IS NOT NULL
            AND certification_version IS NOT NULL AND valid_until IS NOT NULL AND language IS NOT NULL)));
CREATE INDEX qb_certificate_holder_idx ON qb_certificate (user_id, subject_kind, issued_at);
-- The operators' lookup by the graph the certificate came from.
CREATE INDEX qb_certificate_tree_idx ON qb_certificate (tree_id, issued_at);
