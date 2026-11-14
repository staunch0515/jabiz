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
-- Built plainly, in the migration's transaction, as the application's other indexes: the application's Flyway
-- (spring.flyway) defines no ${concurrently} for production and takes its transactional lock, under which a
-- CREATE INDEX CONCURRENTLY would wait for Flyway's own open transaction for ever (platform V66, decision D81). The
-- fees and the payments are written a few times a month per Sponsor; the build holds their writes for its seconds.
CREATE INDEX IF NOT EXISTS qb_fee_charge_version_operator_period_idx ON qb_fee_charge_version (operator_id, period);
CREATE INDEX IF NOT EXISTS qb_fee_payment_version_credit_paid_idx ON qb_fee_payment_version (paid_at)
    WHERE sub_account = 'FEE_CREDIT';
