-- F-M12 d, #701 review M1 and M3: two indexes for the operators' settlements' reads.
--
-- (operator_id, period) on the fees: the waiting list (qb.system.operator-settlements-waiting) and the readiness
-- template ask, for an operator and a month, whether a postpaid fee of the month has no invoice yet. With only
-- (operator_id, charged_at) that reads every fee of the operator, years of them; with this it reads the month's.
--
-- (paid_at) of the parts paid with granted credit: the daily check (qb.system.recon-operator-settlement, credit_parts)
-- reads every such part from the earliest switch point on, not narrowed by a window. Most parts are paid in cash; the
-- partial index holds the granted credit's only.
--
-- Built without blocking the fees' and payments' writes, as V127: CONCURRENTLY through the placeholder of
-- application.yml (empty in the tests, which migrate several schemas of one database side by side), outside a
-- transaction (V129__quizbuks_operator_settlement_indexes.sql.conf: each statement commits by itself), with Flyway's
-- session-level lock (docs/guide/platform-operations.md section 6). A build that failed half way leaves an INVALID
-- index of its name that IF NOT EXISTS keeps: drop it by hand (DROP INDEX CONCURRENTLY with its name), run flyway
-- repair and restart; the startup check SCHEMA warns of an INVALID index.
CREATE INDEX ${concurrently} IF NOT EXISTS qb_fee_charge_version_operator_period_idx
    ON qb_fee_charge_version (operator_id, period);
CREATE INDEX ${concurrently} IF NOT EXISTS qb_fee_payment_version_credit_paid_idx ON qb_fee_payment_version (paid_at)
    WHERE sub_account = 'FEE_CREDIT';
