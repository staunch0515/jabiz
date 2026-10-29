-- Q51b (docs/quizbuks/plans/Q51-publication-versions.md §6): a publication that switched its version answers other
-- questions under the same numbers, so the question statistics are kept per version: each attempt counts for the
-- version it ran on, and a version's figures are shown over the minimum group of its own completions (E-4).

-- The cursor's question totals become {"<version>": {"n": attempts, "q": [[no, completions, correct], ...]}}: room for
-- the versions a publication ran on (each of up to 100 questions).
ALTER TABLE qb_analytics_cursor ALTER COLUMN questions TYPE varchar(400000);

-- Each shown question's version, and the completions of that version the figures were shown over (the minimum group
-- is a version's: two looks a completion apart never tell one person's answers on it).
ALTER TABLE qb_question_stat
    ADD COLUMN version_id uuid,
    ADD COLUMN based_on   numeric(9, 0) CHECK (based_on >= 0);

-- A publication that switched before this migration counted both versions' answers under the same numbers: its
-- totals and shown questions are dropped and the attempts read again from the first (the job reads them in chunks,
-- after the watermark; the overview's figures stay until the new totals pass them by the minimum group).
DELETE FROM qb_question_stat s
WHERE EXISTS (SELECT 1 FROM qb_publication_past_version p WHERE p.publication_id = s.publication_id);
UPDATE qb_analytics_cursor c
SET analysed = 0, submitted = 0, expired = 0, duration_sec = 0, questions = '{}', watermark_at = NULL,
    watermark_id = NULL, finalized_at = NULL, version = c.version + 1
WHERE EXISTS (SELECT 1 FROM qb_publication_past_version p WHERE p.publication_id = c.publication_id);

-- Every other publication ran on the one version its card has: its totals and shown questions are that version's.
UPDATE qb_question_stat s
SET version_id = k.version_id, based_on = c.shown_analysed
FROM qb_publication_card k, qb_analytics_cursor c
WHERE k.publication_id = s.publication_id AND c.publication_id = s.publication_id;
DELETE FROM qb_question_stat WHERE version_id IS NULL OR based_on IS NULL;
UPDATE qb_analytics_cursor c
SET questions = CASE WHEN c.questions IN ('', '[]') THEN '{}'
                     ELSE CAST(jsonb_build_object(CAST(k.version_id AS text),
                         jsonb_build_object('n', c.analysed, 'q', CAST(c.questions AS jsonb))) AS text) END,
    version = c.version + 1
FROM qb_publication_card k
WHERE k.publication_id = c.publication_id AND c.questions NOT LIKE '{%';

ALTER TABLE qb_question_stat
    ALTER COLUMN version_id SET NOT NULL,
    ALTER COLUMN based_on SET NOT NULL,
    DROP CONSTRAINT qb_question_stat_once,
    ADD CONSTRAINT qb_question_stat_once UNIQUE (publication_id, version_id, question_no);
