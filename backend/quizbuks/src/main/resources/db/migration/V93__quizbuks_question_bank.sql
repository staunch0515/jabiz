-- F-S6 (docs/quizbuks/plans/impl/F-S6.md): a quiz is a question bank of up to qb.content.max-questions questions
-- (1000 by default, at most ContentLimits.MAX_QUESTIONS_TECH = 2000); one attempt gives at most
-- qb.play.max-questions-per-attempt (100 by default, at most PlayLimits.MAX_PER_ATTEMPT_TECH = 200), drawn from a
-- larger bank. An attempt's columns are sized for 200 questions (PlayLimits): widened, never narrowed; question_set
-- also fixes its width to the 2 + n x 5 characters PlayLimits always computed (it was 500 for 502).
ALTER TABLE qb_attempt ALTER COLUMN option_order TYPE varchar(3004);
ALTER TABLE qb_attempt ALTER COLUMN answers TYPE varchar(3004);
ALTER TABLE qb_attempt ALTER COLUMN result TYPE varchar(24004);
ALTER TABLE qb_attempt ALTER COLUMN question_set TYPE varchar(1002);
