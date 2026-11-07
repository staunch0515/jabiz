-- F-S1 (docs/quizbuks/first-launch-spec.md, docs/quizbuks/plans/impl/F-S1.md): every sponsor belongs to an operator.
-- The platform is in test and its database is reset, so nothing is carried over.

-- The operator that naturally arriving sponsors (who apply on their own, not by a prospecting trial) belong to: one
-- at most, the platform operator QB_SETUP makes.
ALTER TABLE qb_operator ADD COLUMN default_for_new boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX ux_qb_operator_default_for_new ON qb_operator (default_for_new) WHERE default_for_new;
-- The kinds of publisher it takes (comma-separated, of SPONSOR and SHOPIFY_SHOP; only SPONSOR in the first launch).
ALTER TABLE qb_operator ADD COLUMN publisher_kinds varchar(40) NOT NULL DEFAULT 'SPONSOR';
-- The operator's own sponsor: its first lead's account, set in the same transaction as that lead; empty until then.
ALTER TABLE qb_operator ADD COLUMN direct_sponsor_id varchar(128);
CREATE UNIQUE INDEX ux_qb_operator_direct_sponsor ON qb_operator (direct_sponsor_id)
    WHERE direct_sponsor_id IS NOT NULL;

-- The languages no longer decide where a sponsor goes: an operator's language is shown and used by prospecting only,
-- so two operators may share one.
DROP INDEX ux_qb_operator_active_language;

-- A direct sponsor's assignment is fixed: it is never reassigned.
ALTER TABLE qb_sponsor_operator ADD COLUMN fixed boolean NOT NULL DEFAULT false;

-- The official publishers are the operators' direct sponsors now.
DROP TABLE qb_official_publisher;
