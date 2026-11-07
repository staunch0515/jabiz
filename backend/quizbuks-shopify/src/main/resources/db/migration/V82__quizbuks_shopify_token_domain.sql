-- F-F14 (platform decision D78): a shop's tokens are sealed for its address, and the key rotation computes that
-- context from the token's row alone (SealedField), so the row keeps the address too. Written by QBS_SHOP_CONNECT
-- from now on; rows from before stay empty (no backfill here: entity rows are written through the platform's write
-- path). Shopify is not in the first launch, so such rows exist in test databases only: reset the database or connect
-- the shop again before changing the seal key (docs/quizbuks/plans/impl/F-F14-sealed-fields.md).
ALTER TABLE qbs_shop_token ADD COLUMN shop_domain varchar(255);
