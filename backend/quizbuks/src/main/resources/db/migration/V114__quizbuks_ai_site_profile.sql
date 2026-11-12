-- F-C7 (docs/quizbuks/plans/impl/F-C7.md): an AI job may draft a sponsor's profile package and a "get to know us" quiz
-- from the sponsor's website (SITE_PROFILE). Such a job names no quiz, so the job's quiz becomes optional; every other
-- kind still has one (the processes that start them set it).
ALTER TABLE qb_ai_job DROP CONSTRAINT qb_ai_job_kind_check;
ALTER TABLE qb_ai_job ADD CONSTRAINT qb_ai_job_kind_check CHECK (kind IN ('QUESTIONS', 'COVER', 'TRANSLATION',
    'PUBLICATION_TEXTS', 'TRANSLATION_CHECK', 'SITE_PROFILE'));
ALTER TABLE qb_ai_job ALTER COLUMN quiz_id DROP NOT NULL;
ALTER TABLE qb_ai_job ADD CONSTRAINT qb_ai_job_quiz CHECK (quiz_id IS NOT NULL OR kind = 'SITE_PROFILE');
