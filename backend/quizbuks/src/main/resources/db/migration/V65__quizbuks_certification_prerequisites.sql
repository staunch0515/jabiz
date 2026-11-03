-- Q86 (docs/quizbuks/plans/Q86-agreement-quiz-tree.md, D-Q86-5): a prerequisite may be a certification the taker
-- must hold now (one of the catalog's, certification.Certifications) instead of another publication: one or the
-- other. A certification's requirement is stored as PASSED. An ordinary table: no sealing, columns may change.
ALTER TABLE qb_publication_prerequisite ADD COLUMN certification varchar(40);
ALTER TABLE qb_publication_prerequisite ALTER COLUMN required_publication_id DROP NOT NULL;
ALTER TABLE qb_publication_prerequisite
    ADD CONSTRAINT ck_qb_publication_prerequisite_one CHECK ((required_publication_id IS NULL) <> (certification IS NULL));
-- A publication requires a certification once (NULLs are distinct in UNIQUE: the publication rows do not collide).
ALTER TABLE qb_publication_prerequisite
    ADD CONSTRAINT uk_qb_publication_prerequisite_cert UNIQUE (publication_id, certification);
