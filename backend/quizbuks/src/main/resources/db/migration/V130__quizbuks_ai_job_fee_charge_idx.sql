-- F-M9a-2b (docs/quizbuks/plans/impl/F-M9.md §8, #699 review L3): the daily check AI_FEE_MONTH finds the jobs an AI
-- charge took in by their fee_charge_id (qb.system.recon-ai-credit, the charges of the last 40 days). Only charged jobs
-- carry one. Built without blocking writes, as V127 (the .sql.conf beside it runs it outside a transaction).
CREATE INDEX ${concurrently} IF NOT EXISTS qb_ai_job_fee_charge_idx ON qb_ai_job (fee_charge_id)
    WHERE fee_charge_id IS NOT NULL;
