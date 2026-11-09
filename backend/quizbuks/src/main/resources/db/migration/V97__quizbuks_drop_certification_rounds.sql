-- F-S2c2b (docs/quizbuks/plans/impl/F-S2.md §7 D1): the certifications' rounds (Q69) are gone with the certifications;
-- the knowledge map's nodes replace them. Nothing reads the table any more, and its rows are not carried over (the
-- spec: a migration moves no data). qb_certification_record stays, read only: the certificates issued before (D8)
-- read their revocations from it.
DROP TABLE qb_certification_round;

-- The Sponsor-apply guard (D7, platform decision D80) reads a person's revocations by their id alone; the subject's
-- index leads with the kind.
CREATE INDEX qb_tree_lit_revocation_version_subject_id_idx ON qb_tree_lit_revocation_version (subject_id);
