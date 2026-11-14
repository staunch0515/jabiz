-- F-M15c review L-a: the newest of an erased sponsor's AI jobs (the platform's job sequence) the wind-up has seen. Once
-- the look down (ai_jobs_before_seq) is done, a wind-up not closed looks above it each run, the oldest first, and moves it
-- up: a job started between the erasure and the first look is cancelled too. None until a look read the jobs.
ALTER TABLE qb_sponsor_closure ADD COLUMN ai_jobs_seen_seq numeric(19, 0)
    CONSTRAINT qb_sponsor_closure_ai_jobs_seen_seq CHECK (ai_jobs_seen_seq >= 0);
