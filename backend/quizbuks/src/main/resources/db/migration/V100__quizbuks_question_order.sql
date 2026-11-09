-- F-S7 (docs/quizbuks/plans/impl/F-S7.md): question order and difficulty. Only columns are added to the append-only
-- tables (D58).
-- §1: whether a quiz's questions are shuffled for each taker. Every quiz, the existing ones too, shuffles by default
-- (A2-14); a version written while it was off says "questionOrder":"fixed" in its snapshot, others (the older ones
-- too) are shuffled.
ALTER TABLE qb_quiz_version ADD COLUMN shuffle_questions boolean NOT NULL DEFAULT true;
-- §2: a question's difficulty (EASY, MEDIUM or HARD; none when not set), and a publication's draw by difficulty: how
-- many of each (JSON {"EASY":a,...}), frozen with the policy and copied to the card; its pick_count is then their sum.
ALTER TABLE qb_question_version ADD COLUMN difficulty varchar(8);
ALTER TABLE qb_publication_version ADD COLUMN pick_by_difficulty text;
ALTER TABLE qb_publication_card ADD COLUMN pick_by_difficulty text;
-- §3 (F-S6 §4): how many finished attempts were given each question. Before F-S7 it was what completions counts (an
-- attempt's result has every question it was given).
ALTER TABLE qb_question_stat ADD COLUMN drawn numeric(9, 0);
UPDATE qb_question_stat SET drawn = completions;
ALTER TABLE qb_question_stat ALTER COLUMN drawn SET NOT NULL;
ALTER TABLE qb_question_stat ADD CONSTRAINT qb_question_stat_drawn CHECK (drawn >= 0);
