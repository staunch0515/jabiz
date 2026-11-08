-- F-S6a2 (docs/quizbuks/plans/impl/F-S6.md §3.4): questions imported into an existing quiz in batches, each batch
-- undoable once. A question records its batch and a hash of what the batch added (the question and its options), so
-- undoing removes only the questions not changed since.
ALTER TABLE qb_question_version ADD COLUMN import_id varchar(36);
ALTER TABLE qb_question_version ADD COLUMN import_hash varchar(64);
CREATE INDEX qb_question_version_import_idx ON qb_question_version (import_id) WHERE import_id IS NOT NULL;

-- Written only by QB_QUIZ_IMPORT_QUESTIONS and QB_QUESTION_IMPORT_UNDO (a plain table: undoing sets undone_at).
CREATE TABLE qb_question_import (
    import_id      varchar(36)   PRIMARY KEY,
    quiz_id        varchar(36)   NOT NULL,
    owner_id       varchar(128)  NOT NULL,
    question_count numeric(6, 0) NOT NULL,
    imported_at    timestamptz   NOT NULL,
    undone_at      timestamptz,
    removed_count  numeric(6, 0),
    version        bigint        NOT NULL
);
CREATE INDEX qb_question_import_quiz_idx ON qb_question_import (quiz_id, imported_at DESC);
