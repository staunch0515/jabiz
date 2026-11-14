-- F-M9a-2 (#692 re-review M1, the coordinator 2026-11-14): the daily reconciliation's note AI_RESERVATION_SHORT reads
-- the charged AI jobs the platform took part of on (absorbed_yen > 0) of the month before the run's and after
-- (qb.system.recon-help-usage). Without an index the check scans qb_ai_job, whose history only grows; this partial
-- index holds those few jobs only, by month, its condition the check's own (fee_mode = 'CREDIT' AND absorbed_yen > 0)
-- so that the planner can use it.
--
-- Built without blocking the AI requests' writes: ${concurrently} (CONCURRENTLY; empty in the tests, which migrate
-- several schemas of one database side by side), outside a transaction (V127__quizbuks_ai_job_absorbed_idx.sql.conf),
-- with Flyway's session-level lock (application.yml: spring.flyway.postgresql.transactional-lock=false; see
-- docs/guide/platform-operations.md section 6). A build that failed half way leaves an INVALID index of this name that
-- IF NOT EXISTS keeps: drop it by hand (DROP INDEX CONCURRENTLY qb_ai_job_absorbed_idx), run flyway repair and restart;
-- the startup check SCHEMA warns of an INVALID index.
CREATE INDEX ${concurrently} IF NOT EXISTS qb_ai_job_absorbed_idx ON qb_ai_job (fee_period)
    WHERE fee_mode = 'CREDIT' AND absorbed_yen > 0;
