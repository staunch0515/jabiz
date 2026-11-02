-- Q81 (docs/quizbuks/plans/Q81-question-pick.md): a publication may give each taker k of its version's n questions,
-- drawn at the start (D-Q81-1, D-Q81-3). The count is the publication's setting (frozen with the policy; the
-- publication's full score is then k times the common points), copied to its card; the questions an attempt drew
-- (the version's numbers, in the version's order) are written with the attempt, never read back by an interface.
ALTER TABLE qb_publication_version ADD COLUMN pick_count integer;
ALTER TABLE qb_publication_card ADD COLUMN pick_count integer;
ALTER TABLE qb_attempt ADD COLUMN question_set varchar(500);
