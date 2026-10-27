-- A channel's publications (docs/quizbuks/shopify/S2-storefront.md, S2-2): a shop publishes on its own storefront,
-- live at once, with the reward method CHANNEL (the engine scores and settles nothing; the shop hands out its own
-- discount code). Only the allowed values of the method change: no column is added, renamed or retyped.
ALTER TABLE qb_publication_version DROP CONSTRAINT qb_publication_version_reward_method_check;
ALTER TABLE qb_publication_version ADD CONSTRAINT qb_publication_version_reward_method_check
    CHECK (reward_method IN ('PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM', 'DIGITAL_PRIZE', 'CHANNEL'));
ALTER TABLE qb_publication_card DROP CONSTRAINT qb_publication_card_reward_method_check;
ALTER TABLE qb_publication_card ADD CONSTRAINT qb_publication_card_reward_method_check
    CHECK (reward_method IN ('PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM', 'DIGITAL_PRIZE', 'CHANNEL'));
