-- F-C7b-2 (docs/quizbuks/plans/impl/F-C7.md): the quiz a sponsor made from a website draft. A draft (kind SITE_PROFILE)
-- makes one quiz at most; the column says which, so that the page can link to it and the process can refuse a second.
-- Empty for every other kind and for a draft no quiz was made from.
ALTER TABLE qb_ai_job ADD COLUMN site_quiz_id uuid;
ALTER TABLE qb_ai_job ADD CONSTRAINT qb_ai_job_site_quiz CHECK (site_quiz_id IS NULL OR kind = 'SITE_PROFILE');
CREATE UNIQUE INDEX uk_qb_ai_job_site_quiz ON qb_ai_job (site_quiz_id) WHERE site_quiz_id IS NOT NULL;
