-- AI covers (docs/quizbuks/plans/Q8-ai.md, Q8b, M-25): a cover job's candidate pictures, files of the policy
-- qb.content.image the platform stored as the sponsor's uploads (platform decision D50). They are referred to from the
-- job only while it waits for the sponsor's choice (READY), so the platform's file sweep keeps them until then; once
-- the job is closed (a cover chosen, discarded, expired) the columns are cleared and the pictures not chosen go.
-- Migration numbers (2026-10-18): Q7b V12, Q7a V13, Q9e1 V14, Q7c V15, Q8a V16, Q8b V17.
ALTER TABLE qb_ai_job ADD COLUMN cover_1 uuid;
ALTER TABLE qb_ai_job ADD COLUMN cover_2 uuid;
ALTER TABLE qb_ai_job ADD COLUMN cover_3 uuid;
ALTER TABLE qb_ai_job ADD CONSTRAINT qb_ai_job_covers CHECK (
    (cover_1 IS NULL AND cover_2 IS NULL AND cover_3 IS NULL) OR (status = 'READY' AND kind = 'COVER'));
