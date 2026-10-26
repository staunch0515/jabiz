-- The engine's publisher (docs/quizbuks/shopify/S0-publisher.md, D-S0-1, D-S0-5): every record that is a copy of a
-- publication's owner names the owner by kind (SPONSOR, SHOPIFY_SHOP) and id instead of a sponsor's user id alone.
-- The sponsor_id columns become publisher_id (the values stand: a sponsor's user id), a publisher_kind column is
-- added and every row written so far is a sponsor's. The merchant account's own records (top-ups, receipts, mail
-- server, redeem tokens, invitation quotas, AI jobs) keep their sponsor_id: they belong to the sponsors' system alone.
--
-- One step, as the counter split (V10): QuizBuks runs one instance and is upgraded with the application stopped (the
-- old jar cannot run on the new columns, nor the new one on the old). Only DDL: the append-only triggers of the
-- temporal tables stop UPDATE, DELETE and TRUNCATE, not ALTER TABLE, and no row is rewritten (PostgreSQL stores a
-- constant default in the catalogue). The default is dropped afterwards so that every write names the kind, and a
-- check keeps the kinds to the dictionary's.

CREATE FUNCTION quizbuks_add_publisher(p_table text) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('ALTER TABLE %I RENAME COLUMN sponsor_id TO publisher_id', p_table);
    EXECUTE format('ALTER TABLE %I ADD COLUMN publisher_kind varchar(20) NOT NULL DEFAULT ''SPONSOR''', p_table);
    EXECUTE format('ALTER TABLE %I ALTER COLUMN publisher_kind DROP DEFAULT', p_table);
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (publisher_kind IN (''SPONSOR'', ''SHOPIFY_SHOP''))',
        p_table, p_table || '_publisher_kind_check');
END $$;

-- The view over the cards is dropped first: it names the card's sponsor_id.
DROP VIEW qb_publication_owner;

-- Temporal (append-only) tables.
SELECT quizbuks_add_publisher('qb_publication_version');
SELECT quizbuks_add_publisher('qb_reward_tier_version');
SELECT quizbuks_add_publisher('qb_cash_reward_version');
SELECT quizbuks_add_publisher('qb_redemption_version');
SELECT quizbuks_add_publisher('qb_digital_prize_version');
-- Ordinary tables.
SELECT quizbuks_add_publisher('qb_publication_card');
SELECT quizbuks_add_publisher('qb_attempt');
SELECT quizbuks_add_publisher('qb_analytics_hour');
SELECT quizbuks_add_publisher('qb_analytics_cursor');
SELECT quizbuks_add_publisher('qb_question_stat');
SELECT quizbuks_add_publisher('qb_digital_code');
SELECT quizbuks_add_publisher('qb_invitation');

DROP FUNCTION quizbuks_add_publisher(text);

-- The indexes follow their column's name (a renamed column keeps its indexes; only the names say "sponsor").
ALTER INDEX qb_publication_version_sponsor_idx RENAME TO qb_publication_version_publisher_idx;
ALTER INDEX qb_reward_tier_version_sponsor_idx RENAME TO qb_reward_tier_version_publisher_idx;
ALTER INDEX qb_publication_card_sponsor_idx RENAME TO qb_publication_card_publisher_idx;
ALTER INDEX qb_analytics_hour_sponsor_idx RENAME TO qb_analytics_hour_publisher_idx;
ALTER INDEX qb_cash_reward_version_sponsor_idx RENAME TO qb_cash_reward_version_publisher_idx;
ALTER INDEX qb_redemption_version_sponsor_idx RENAME TO qb_redemption_version_publisher_idx;
ALTER INDEX qb_invitation_sponsor_idx RENAME TO qb_invitation_publisher_idx;
ALTER INDEX qb_digital_code_sponsor_idx RENAME TO qb_digital_code_publisher_idx;
ALTER INDEX qb_digital_prize_version_sponsor_idx RENAME TO qb_digital_prize_version_publisher_idx;
-- qb_attempt_pending_idx (publisher_id, submitted_at, attempt_id) WHERE reward_status = 'PENDING' keeps its name: the
-- pending rewards are a sponsor's wallet's, found by the publisher that is the sponsor.

-- Who published each card, now with the kind: read through the dataset limited to the caller (a sponsor's own).
CREATE VIEW qb_publication_owner AS
    SELECT publication_id, publisher_kind, publisher_id FROM qb_publication_card;
