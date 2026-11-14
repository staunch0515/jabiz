-- F-M9 b2-1: the AI fees are read by month. The admin template qb.admin.ai-fees (a row for each month, sponsor and
-- operator), the operator's template qb.operator.sponsor-ai (a sponsor's fee of a month) and the daily alert on what
-- the platform took on (qb.system.ai-absorbed, a month's total) all ask for the jobs of a fee month. Without an index
-- they scan qb_ai_job, whose history only grows; this partial index holds the jobs whose result was recorded (a
-- fee_period), by month and sponsor, its condition the queries' own.
--
-- Built without blocking the AI requests' writes, as V127 and V129: CONCURRENTLY through the Flyway placeholder named
-- concurrently (empty in the tests, which migrate several schemas of one database side by side), outside a
-- transaction (V132__quizbuks_ai_job_fee_period_idx.sql.conf), with Flyway's session-level lock
-- (docs/guide/platform-operations.md section 6). A build that failed half way leaves an INVALID index of this name
-- that IF NOT EXISTS keeps: drop it by hand (DROP INDEX CONCURRENTLY qb_ai_job_fee_period_idx), run flyway repair and
-- restart; the startup check SCHEMA warns of an INVALID index.
CREATE INDEX ${concurrently} IF NOT EXISTS qb_ai_job_fee_period_idx ON qb_ai_job (fee_period, sponsor_id)
    WHERE fee_period IS NOT NULL;
