-- F-C7b-2 (docs/quizbuks/plans/impl/F-C7.md): the quiz a sponsor made from a website draft. A draft (kind SITE_PROFILE)
-- makes one quiz at most; the column says which, so that the page can link to it and the process can refuse a second.
-- Empty for every other kind and for a draft no quiz was made from.
ALTER TABLE qb_ai_job ADD COLUMN site_quiz_id uuid;
ALTER TABLE qb_ai_job ADD CONSTRAINT qb_ai_job_site_quiz CHECK (site_quiz_id IS NULL OR kind = 'SITE_PROFILE');
CREATE UNIQUE INDEX uk_qb_ai_job_site_quiz ON qb_ai_job (site_quiz_id) WHERE site_quiz_id IS NOT NULL;

-- The questions kept for review next to that quiz are a job of their own: they say which website draft they came from,
-- so that they are not counted as a request of the day (the website job counted what the AI used).
ALTER TABLE qb_ai_job ADD COLUMN from_site_draft_id varchar(36);
ALTER TABLE qb_ai_job ADD CONSTRAINT qb_ai_job_from_site_draft CHECK (from_site_draft_id IS NULL OR kind = 'QUESTIONS');
